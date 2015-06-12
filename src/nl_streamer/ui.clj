(ns nl-streamer.ui
  (:require [clojure.data :refer (diff)]
            [seesaw.core :as ssc]
            [nl-streamer.serial-port :refer (port-names)]
            [nl-streamer.neurosky :as nsky]
            [nl-streamer.utils :as u]
            [nl-streamer.vlc :as vlc]))

(def ^:private window (ssc/frame :title "Neurolyzer Streamer"))

(def ^:private nl-button-texts {:disconnected "Connect"
                                :connected "Disconnect"})

(def ^:private vlc-button-texts {:disconnected "Start control VLC"
                                 :connected "Stop control VLC"})

(def ^:private label-texts {:disconnected "Not Connected"
                            :connected "Connected"})

(def ^:private combo-enabled {:disconnected true
                              :connected false})

(def ^:private backend->consume-fn
  {"Neurolyzer" u/send-stat-to-neurolyzer
   "VLC" vlc/send-play-command})

(defn alter-connection! [consume-fn]
  (if (= :disconnected (nsky/get-connection-status))
    (let [path (ssc/config (ssc/select window [:#ports]) :text)]
      (when path (nsky/start! path consume-fn)))
    (nsky/stop!)))

(defn reconfig-ui! [status]
  (let [btn (ssc/select window [:#connect])
        lbl (ssc/select window [:#status])
        combo (ssc/select window [:#ports])]
    (ssc/config! btn :text (get nl-button-texts status))
    (ssc/config! lbl :text (get label-texts status))
    (ssc/config! combo :enabled? (get combo-enabled status))))

(defn stream-button-click [_]
  (alter-connection! (get backend->consume-fn "Neurolyzer"))
  (reconfig-ui! (nsky/get-connection-status)))

(defn make-stream-button []
  (ssc/button :id :nl-connect
              :text (:disconnected nl-button-texts)
              :listen [:action stream-button-click]))

(defn- make-neurolyzer-panel []
  (let [conf (:config u/*options*)]
    (ssc/grid-panel
     :id :neurolyzer-panel
     :columns 4
     :items [(ssc/label :text "Protocol")
             (ssc/combobox :id :nl-protocol :model ["HTTP" "HTTPS"])
             (ssc/label :text "Host")
             (ssc/text :id :nl-host :text (get conf :host ""))
             (ssc/label :text "Profile ID")
             (ssc/text :id :nl-profile-id :text (get conf :profile-id ""))
             (ssc/label :text "Exercise ID")
             (ssc/text :id :nl-exercise-id :text (get conf :exercise-id ""))
             (ssc/label :text "Token")
             (ssc/text :id :nl-token :text (get conf :token ""))
             (ssc/label :id :status :text (:disconnected label-texts))
             (make-stream-button)])))

(defn- generate-playlist-item-id [item-id & {:keys [prefix postfix]}]
  (keyword (str prefix "vlc-playlist-item-" item-id postfix)))

(defn- make-playlist-item [item-name item-id]
  (let [gen-id (partial generate-playlist-item-id item-id)]
    (ssc/grid-panel
     :id (gen-id)
     :columns 2
     :items [(ssc/label item-name)
             (ssc/horizontal-panel
              :items
              [(ssc/combobox :id (gen-id :postfix "-metric")
                             :model ["disabled" "attention" "meditation"])
               (ssc/label :text ">=")
               (ssc/text :id (gen-id :postfix "-gte"))
               (ssc/label :text "<=")
               (ssc/text :id (gen-id :postfix "-lte"))])])))

(defn- update-vlc-config! []
  (vlc/update-config!
   {:host (ssc/config (ssc/select window [:#vlc-host]) :text)
    :password (ssc/config (ssc/select window [:#vlc-password]) :text)}))

(defn- vlc-refresh-button-click [_]
  (update-vlc-config!)
  (let [new-plist-items (vlc/receive-playlist)
        plist-items (vlc/get-playlist)
        [to-remove to-add _] (diff (:items plist-items)
                                   (:items new-plist-items))
        vlc-panel (ssc/select window [:#vlc-panel])]
    (when (seq to-remove)
      #_(println "to-remove: " to-remove)
      (doall
       (map (fn [[_ id]]
              (ssc/remove!
               vlc-panel
               (ssc/select window
                           [(generate-playlist-item-id id :prefix "#")])))
            to-remove)))
    (when (seq to-add)
      #_(println "to-add: " to-add)
      (doall (map #(ssc/add! vlc-panel (apply make-playlist-item %)) to-add)))
    (vlc/set-playlist! new-plist-items)))

(defn- make-vlc-refresh-button []
  (ssc/button :id :refresh-vlc
              :text "Refresh Tracks"
              :listen [:action vlc-refresh-button-click]))

(defn- get-vlc-item-prop [item-id postfix]
  (let [ui-id (generate-playlist-item-id item-id
                                         :prefix "#"
                                         :postfix postfix)]
    (ssc/config (ssc/select window [ui-id]) :text)))

(defn- get-vlc-item-rule [id]
  (let [get-prop (partial get-vlc-item-prop id)
        metric (get-prop "-metric")]
    (when (not= "disabled" metric)
      (let [lte (get-prop "-lte")
            gte (get-prop "-gte")]
        {:id id, :metric metric, :lte lte, :gte gte}))))

(defn- get-vlc-rules []
  (let [items-ids (vals (:items (vlc/get-playlist)))]
    (filter map? (map #(get-vlc-item-rule %) items-ids))))

(defn- vlc-start-button-click [evt]
  (update-vlc-config!)
  (let [rules (get-vlc-rules)]
    (when (seq rules)
      (alter-connection! (partial (get backend->consume-fn "VLC") rules))
      (ssc/config! (.getSource evt)
                   :text (get vlc-button-texts (nsky/get-connection-status))))))

(defn- make-vlc-start-button []
  (ssc/button :id :start-vlc
              :text (:disconnected vlc-button-texts)
              :listen [:action vlc-start-button-click]))

(defn- make-vlc-panel []
  (ssc/vertical-panel
   :id :vlc-panel
   :visible? false
   :items [(ssc/grid-panel
            :columns 4
            :items [(ssc/label :text "Host:Port")
                    (ssc/text :id :vlc-host :text "localhost:8080")
                    (ssc/label :text "Password")
                    (ssc/text :id :vlc-password)
                    (make-vlc-refresh-button)
                    (ssc/label)
                    (ssc/label)
                    (make-vlc-start-button)])]))

(defn- handle-backend-selection [evt]
  (let [bknd (ssc/text (.getSource evt))
        neurolyzer-panel (ssc/select window [:#neurolyzer-panel])
        vlc-panel (ssc/select window [:#vlc-panel])]
    (ssc/config! neurolyzer-panel :visible? (= "Neurolyzer" bknd))
    (ssc/config! vlc-panel :visible? (= "VLC" bknd))))

(defn- make-main-panel []
  (ssc/horizontal-panel
   :items [(ssc/combobox :id :ports :model (port-names))
           :separator
           (ssc/label :text "Select the backend")
           (ssc/combobox :id :interfaces
                         :listen [:action handle-backend-selection]
                         :model (keys backend->consume-fn))]))

(defn- make-window-content []
  (ssc/vertical-panel
   :items [(make-main-panel)
           (make-neurolyzer-panel)
           (make-vlc-panel)]))

(defn show-window! [_]
  (-> window
      (ssc/config! :content (make-window-content))
      (ssc/pack!)
      (ssc/show!)))
