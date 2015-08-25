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

(def ^:private data-stream (atom nil))

(def ^:private next-command-in (atom 0))

(defn get-playlist [] @vlc-playlist)

(defn set-playlist! [new-plist] (reset! vlc-playlist new-plist))

(defn update-config! [new-config]
  (reset! data-stream nil)
  (reset! next-command-in (:command-inerval new-config))
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

(defn- metric-complies-rule? [metric-value rule]
  (let [gte (str->int-safely (:gte rule))
        lte (str->int-safely (:lte rule))]
    (and (>= metric-value gte) (<= metric-value lte))))

(defn- process-stat->data-stream! [config stat]
  (let [metric ((:stat-process-fn config) stat)]
    (swap! data-stream #(take (:average-interval config) (cons metric %)))))

(defn send-play-command
  "Updates data-stream with appropriate metric, calculated from stat.
  If next-command-in is 0 and length of data-stream is :average-interval,
  calculate average metric level, find first playlist item, which rule complies,
  and send play command with its ID."
  [rules stat]
  (let [conf @vlc-config
        data (process-stat->data-stream! conf stat)]
    #_(println "Last data:" data)
    (when (= (count data) (:average-interval conf))
      (if (> @next-command-in 0)
        (swap! next-command-in dec)
        (do
          (reset! next-command-in (dec (:command-inerval conf)))
          (let [avg-metric (/ (reduce + data) (count data))
                compl-fn (partial metric-complies-rule? avg-metric)
                win-rule (first (filter compl-fn rules))
                win-id (get win-rule :id)]
            #_(println "Command! Metric: " (int avg-metric) ", ID: " win-id)
            (when (and win-id (not= win-id (:current (get-playlist))))
              (send-request "status" :command "pl_play" :id win-id)
              (swap! vlc-playlist assoc :current win-id))))))))
