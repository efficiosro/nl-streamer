(ns nl-streamer.utils
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clj-http.lite.client :as client]))

(def ^:dynamic *options* {})

(def ^:private http-client-opts {:content-type :json
                                 :accept :json
                                 :socket-timeout 1000
                                 :conn-timeout 1000})

(defn- make-url [opts]
  (str (:protocol opts "http") "://" (:host opts)
       "/profiles/" (:profile-id opts)
       "/exercises/" (:exercise-id opts)
       "/statistics/add.json"))

(defn- config-file->options [path]
  (let [conf (json/parse-string (slurp path) true)
        url (make-url conf)
        headers {"Authorization" (:token conf)}]
    {:url url, :http-opts (assoc http-client-opts :headers headers)}))

(defn- set-options! [opts]
  (alter-var-root (var *options*) (constantly opts)))

(defn setup!
  "Reads JSON config from file, makes options and alter *options* variable."
  [path-to-config]
  (set-options! (config-file->options path-to-config)))

(defn send-stat
  "Accepts a stat and sends it to the Neurolyzer service."
  [stat]
  (let [body (json/generate-string {:stat stat})]
    (try
      (client/post (:url *options*)
                   (assoc (:http-opts *options*) :body body))
      (catch Exception ex
        (log/error ex "Stat is not sent")))))
