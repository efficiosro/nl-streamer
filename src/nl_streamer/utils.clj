(ns nl-streamer.utils
  (:require [cheshire.core :as json]
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

(defn config->options [conf]
  (let [url (make-url conf)
        headers {"Authorization" (:token conf)}
        http-opts (assoc http-client-opts :headers headers)]
    {:url url, :http-opts http-opts, :config conf}))

(defn set-options! [opts]
  (alter-var-root (var *options*) (constantly opts)))

(defn send-stat-to-neurolyzer
  "Accepts a stat and sends it to the Neurolyzer service."
  [stat]
  (let [body (json/generate-string {:stat stat})
        http-opts (assoc (:http-opts *options*) :body body)]
    (try
      (client/post (:url *options*) http-opts)
      (catch Exception ex
        (println (str "Stat was not sent to Neurolyzer: " (.getMessage ex)))))))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn setup!
  "Reads JSON config from file, makes options and alter *options* variable."
  [path-to-config]
  (try
    (set-options!
     (config->options (json/parse-string (slurp path-to-config) true)))
    (catch Exception ex
      (exit 5 (str "Cannot setup nl-streamer: " (.getMessage ex))))))

(defn setup-and-start! [opts start-fn! stop-fn!]
  (when (:config opts) (setup! (:config opts)))
  (when-let [device (start-fn! opts)]
    (when stop-fn!
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop-fn!))))
    device))

(defn str->int-safely
  ([maybe-int-str]
   (str->int-safely maybe-int-str 0))
  ([maybe-int-str fallback]
   (try
     (Integer/parseInt maybe-int-str)
     (catch Exception _ fallback))))
