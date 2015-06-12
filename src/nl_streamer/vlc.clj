(ns nl-streamer.vlc
  (:require [clojure.string :refer [join]]
            [cheshire.core :as json]
            [clj-http.lite.client :as client]
            [nl-streamer.utils :refer [str->int-safely]]))

(def ^:private vlc-http-opts {:content-type :json
                              :accept :json
                              :socket-timeout 1000
                              :conn-timeout 1000})

(def ^:private vlc-playlist (atom {}))

(def ^:private vlc-config (atom {}))

(defn get-playlist [] @vlc-playlist)

(defn set-playlist! [new-plist] (reset! vlc-playlist new-plist))

(defn update-config! [new-config]
  (swap! vlc-config merge new-config))

(defn- make-url [host request opts]
  (let [burl (str "http://" host "/requests/" request ".json")]
    (if (seq opts)
      (str burl "?" (join "&" (map (fn [[k v]] (str (name k) "=" v)) opts)))
      burl)))

(defn send-request [request & {:as opts}]
  (let [conf @vlc-config
        url (make-url (:host conf) request opts)
        http-opts (assoc vlc-http-opts :basic-auth ["" (:password conf)])]
    (try
      (let [resp (client/get url http-opts)
            [status body] (map (partial get resp) [:status :body])]
        (if (= 200 status)
          (json/parse-string body true)
          (println (str "VLC responsed with status " status ", body: " body))))
      (catch Exception ex
        (println (str "Request to VLC failed: " (.getMessage ex)))))))

(defn receive-playlist []
  (let [all-playlists (send-request "playlist")
        playlist (first (:children all-playlists))]
    (reduce (fn [res item]
              (let [id (:id item)
                    curr? (contains? item :current)
                    r (if curr? (assoc res :current id) res)]
                (assoc-in r [:items (:name item)] id)))
            {}
            (:children playlist))))

(defn- stat-complies-rule? [stat rule]
  (let [metric-value (get stat (keyword (:metric rule)))
        gte (str->int-safely (:gte rule))
        lte (str->int-safely (:lte rule))]
    (and (>= metric-value gte) (<= metric-value lte))))

(defn send-play-command [rules stat]
  (println "Consume stat: " (select-keys stat [:attention :meditation]))
  (let [win-rule (first (filter (partial stat-complies-rule? stat) rules))
        win-id (get win-rule :id)]
    (when (and win-id (not= win-id (:current (get-playlist))))
      (send-request "status" :command "pl_play" :id win-id)
      (swap! vlc-playlist assoc :current win-id))))
