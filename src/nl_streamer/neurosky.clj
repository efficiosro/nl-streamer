(ns nl-streamer.neurosky
  (:require [clojure.java.io :as io]
            [nl-streamer.serial-port :as sp]
            [nl-streamer.utils :as u]
            [clojure.set :refer [superset?]])
  (:import (java.nio ByteBuffer ByteOrder)))

;;; Special codes
(def SYNC                 0xAA)
(def EXCODE               0x55)
;;; Command codes.
;;; Code bytes less than 0x80 have only one byte value.
;;; Codes greater than or equal to 0x80 have multibyte values.
;;; Size of multibyte payload can be extracted from the next to command byte.
;;; Payload size must be less than 0xAA.
(def POOR_SIGNAL          0x02)
(def HEART_RATE           0x03)
(def ATTENTION            0x04)
(def MEDITATION           0x05)
(def BLINK                0x16)
;;; a single big-endian 16-bit two's-compliment signed value 
;;; (high-order byte followed by low-order byte) (-32768 to 32767).
(def RAW_VALUE            0x80)
;;; eight big-endian 4-byte IEEE 754 floating point values representing
;;; delta, theta, low-alpha, high-alpha, low-beta, high-beta, low-gamma,
;;; and mid-gamma EEG band power values.
(def EEG_POWER            0x81)
;;; eight big-endian 3-byte unsigned integer values representing delta,
;;; theta, low-alpha, high-alpha, low-beta, high-beta, low-gamma, and
;;; mid-gamma EEG band power values.
(def ASIC_EEG_POWER       0x83)
;;; two byte big-endian unsigned integer representing
;;; the milliseconds between two R-peaks.
(def RRINTERVAL           0x86)
;;; Packet header is (0xAA 0xAA PAYLOAD_LENGTH), 3 bytes.
(def HEADER_LENGTH 3)
;;; Packet overhead is 4 bytes, header + checksum (latter byte).
(def PACKET_OVERHEAD 4)
;;; Payload length position in packet.
(def LENGTH_POSITION 2)

(def EEG_POWER_METRICS [:delta :theta
                        :low_alpha :high_alpha
                        :low_beta :high_beta
                        :low_gamma :mid_gamma])

(def REQUIRED_FIELDS #{:attention :meditation})
(def FIELDS_NON_GRATA #{:poor-signal :heart-rate :blink :raw-value})

(def ^:private bytes-stream (atom clojure.lang.PersistentQueue/EMPTY))
(def ^:private stats (atom {}))

(defn- conjv [coll & items]
  (apply conj (vec coll) items))

(defn- contains-required-fields? [stat]
  (superset? (set (keys stat)) REQUIRED_FIELDS))

(defn- store-and-maybe-send-stat
  "It parses statistics, that were read from NeuroSky device's serial port,
  updates appropriate stat record, and send previous stat if required."
  [stat]
  (if (and (seq stat)
           (= 0 (get stat :poor-signal 0)))
    (let [now-ms (System/currentTimeMillis)
          now-s (quot now-ms 1000)
          new-record? ((complement contains?) @stats now-s)
          prev-stat (if new-record? (get @stats (- now-s 1)))
          s (apply dissoc stat FIELDS_NON_GRATA)]
      (if (seq s)
        (swap! stats update-in [now-s] merge s))
      (if-let [raw (:raw-value stat)]
        (swap! stats update-in [now-s :environment] conjv [now-ms raw]))
      (if (contains-required-fields? prev-stat)
        (u/send-stat (assoc prev-stat :timestamp (- now-s 1)))))))

(defn- process-raw-value
  "Receives sequence of two bytes, which represents raw data value,
  transforms it to signed integer value and return."
  [bytes]
  (let [hi-b (first bytes)
        lo-b (second bytes)
        v (+ (* hi-b 256) lo-b)
        raw-value (if (> v 32767) (- v 65536) v)]
    {:raw-value raw-value}))

(defn- four-bytes->float [bytes]
  (-> (ByteBuffer/wrap (byte-array bytes))
      (.order ByteOrder/BIG_ENDIAN)
      (.getFloat)))

(defn- process-eeg-power
  "Receives sequence of bytes, which represents EEG power. EEG powers are
  four bytes floats. Returns map of metric to value pairs."
  [bytes]
  (zipmap EEG_POWER_METRICS
          (map #(four-bytes->float %) (partition 4 bytes))))

(defn- three-bytes->int [bytes]
  (reduce #(+ % (* (first %2) (second %2)))
          0
          (partition 2 (interleave '(65536 256 1) bytes))))

(defn- process-eeg-power-asic
  "Receives sequence of bytes, which represents ASIC EEG power. EEG powers are
  three bytes integers. Returns map of metric to value paris."
  [bytes]
  (zipmap EEG_POWER_METRICS
          (map #(three-bytes->int %) (partition 3 bytes))))

(defn- read-one-byte-value
  [payload]
  (let [code (first payload)
        value (second payload)
        stat (condp = code
               POOR_SIGNAL {:poor-signal value}
               HEART_RATE {:heart-rate value}
               ATTENTION {:attention value}
               MEDITATION {:meditation value}
               BLINK {:blink value}
               {})]
    [stat (drop 2 payload)]))

(defn- read-multi-byte-value
  [payload]
  (let [code (first payload)
        v-len (second payload)
        v-bytes (take v-len (drop 2 payload))
        stat (condp = code
               RAW_VALUE (process-raw-value v-bytes)
               EEG_POWER (process-eeg-power v-bytes)
               {})]
    [stat (drop (+ v-len 2) payload)]))

(defn- read-next-value
  "Try to read the next value from the payload. If first byte is EXCODE, returns
  vector with empty stat \"{}\" and rest of the payload. Otherwise, dispatches
  appropriate reader and returns its result."
  [payload]
  (let [code (first payload)]
    (if (= code EXCODE)
      [{} (rest payload)]
      (if (< code RAW_VALUE)
        (read-one-byte-value payload)
        (read-multi-byte-value payload)))))

(defn- valid-payload? [payload checksum]
  (let [sum (reduce + 0 payload)
        chksum (bit-and (bit-not (bit-and sum 0xFF)) 0xFF)]
    (= checksum chksum)))

(defn- process-packet
  [packet]
  (let [payload (butlast (drop HEADER_LENGTH packet))
        checksum (last packet)]
    (when (valid-payload? payload checksum)
      (loop [stat {}
             payload payload]
        (if (seq payload)
          (let [[msg p] (read-next-value payload)]
            (recur (merge stat msg) p))
          (store-and-maybe-send-stat stat))))))

(defn- read-packet-from-stream
  "Reads packet of \"packet-length\" bytes from bytes stream, runs thread to
  process it, then removes \"packet-length\" bytes from bytes stream."
  [packet-length]
  (loop [s @bytes-stream]
    (if (>= (count s) packet-length)
      (let [packet (take packet-length s)]
        (process-packet packet)
        (dotimes [_ packet-length] (swap! bytes-stream pop)))
      (do
        (Thread/sleep 15)
        (recur @bytes-stream)))))

(defn- sync? [coll] (every? (partial = SYNC) coll))

(defn- get-packet-length
  "Returns packet length, if \"coll\" starts with NeuroSky packet header
  (0xAA 0xAA PAYLOAD_LENGTH), where \"PAYLOAD_LENGTH\" must be less than SYNC.
  Packet length is \"PAYLOAD_LENGTH\" + \"PACKET_OVERHEAD\".
  Otherwise, returns nil."
  [coll]
  (let [sync-found (sync? (take 2 coll))
        payload-len (nth coll LENGTH_POSITION)]
    (if (and sync-found (> SYNC payload-len))
      (+ PACKET_OVERHEAD payload-len))))

(defn- process-bytes-stream []
  (loop [s @bytes-stream]
    (if (> HEADER_LENGTH (count s))
      (Thread/sleep 15)
      (if-let [p-len (get-packet-length s)]
        (read-packet-from-stream p-len)
        (swap! bytes-stream pop)))
    (recur @bytes-stream)))

(defn- port->bytes-stream [in-stream]
  (let [input-array (byte-array (.available in-stream))
        input-length (.read in-stream input-array)]
    (doseq [b (take input-length input-array)]
      (swap! bytes-stream conj (bit-and b 0xFF)))))

(defn start!
  "Opens serial port on \"path\", reads data from it to the queue and launches
  thread to read and process data from the queue. Returns map with port
  and listener thread."
  ([path] (start! path 57600))
  ([path baud-rate]
   (reset! bytes-stream clojure.lang.PersistentQueue/EMPTY)
   (reset! stats {})
   (let [port (sp/open path baud-rate)
         _ (sp/write-int port (int 2)) ;; set baud rate to 57.6k
         listener (doto (Thread. process-bytes-stream) (.start))]
     (sp/listen port port->bytes-stream)
     {:port port :listener listener})))

(defn stop! [neurosky]
  (sp/close (:port neurosky))
  (.stop (:listener neurosky)))

;; (require '[nl-streamer.neurosky-reader :as nsr]
;;          '[nl-streamer.serial-port :as sp]
;;          '[clojure.java.io :as io])
;; (def nsky (nsr/start! (sp/port-at 4)))
;; (filter #(or ((complement contains?) % :raw-value)
;;              (> (count (keys %)) 1))
;;         @nsr/stats)
