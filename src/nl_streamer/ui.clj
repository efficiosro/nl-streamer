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

(def ^:private stat-process-fns
  {"Attention" (fn [stat] (get stat :attention 0))
   "Meditation" (fn [stat] (get stat :meditation 0))
   "Att - Med" (fn [stat]
                 (let [att (get stat :attention 0)
                       med (get stat :meditation 0)]
                   (- att med)))
   "Med - Att" (fn [stat]
                 (let [att (get stat :attention 0)
                       med (get stat :meditation 0)]
                   (- med att)))
   "Att + Med" (fn [stat]
                 (let [att (get stat :attention 0)
                       med (get stat :meditation 0)]
                   (+ med att)))})

(def ^:private metric-help-messages
  {"Attention" "Attention interval lies between 0 and 100."
   "Meditation" "Meditation interval lies between 0 and 100."
   "Att - Med" "Attention - Meditation interval lies between -100 and 100."
   "Med - Att" "Meditation - Attention interval lies between -100 and 100."
   "Att + Med" "Attention + Meditation interval lies between 0 and 200."})

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
     :items [(ssc/label :halign :center :text item-name)
             (ssc/horizontal-panel
              :items
              [(ssc/combobox :id (gen-id :postfix "-status")
                             :model ["disabled" "enabled"])
               (ssc/label :text ">=")
               (ssc/text :id (gen-id :postfix "-gte"))
               (ssc/label :text "<=")
               (ssc/text :id (gen-id :postfix "-lte"))])])))

(defn- update-vlc-config! []
  (let [metric (ssc/config (ssc/select window [:#control-metric]) :text)
        stat-process-fn (get stat-process-fns metric)
        avg-int-s (ssc/config (ssc/select window [:#average-interval]) :text)
        avg-interval (u/str->int-safely avg-int-s 1)
        cmd-int-s (ssc/config (ssc/select window [:#command-interval]) :text)
        cmd-interval (u/str->int-safely cmd-int-s 1)]
    (vlc/update-config!
     {:host (ssc/config (ssc/select window [:#vlc-host]) :text)
      :password (ssc/config (ssc/select window [:#vlc-password]) :text)
      :stat-process-fn stat-process-fn
      :average-interval avg-interval
      :command-inerval cmd-interval})))

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
        status (get-prop "-status")]
    (when (not= "disabled" status)
      (let [lte (get-prop "-lte")
            gte (get-prop "-gte")]
        {:id id, :lte lte, :gte gte}))))

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

(defn- handle-control-metric-selection [evt]
  (let [metric (ssc/config (.getSource evt) :text)
        help-label (ssc/select window [:#metric-help-msg])]
    (ssc/config! help-label :text (get metric-help-messages metric))))

(defn- make-vlc-panel []
  (ssc/vertical-panel
   :id :vlc-panel
   :visible? false
   :items [(ssc/form-panel
            :items
            [[nil :fill :horizontal :insets (java.awt.Insets. 2 5 0 5)
              :gridx 0 :gridy 0]
             [(ssc/label :text "Host:Port" :halign :right)]
             [(ssc/text :id :vlc-host :columns 15 :text "localhost:8080")
              :gridx 1 :weightx 1.0 :gridwidth 3]
             [(ssc/label :text "Password" :halign :right)
              :gridx 4 :gridwidth 1]
             [(ssc/text :id :vlc-password :columns 10)
              :gridx 5 :weightx 1.0 :gridwidth 3]
             [(make-vlc-refresh-button) :gridx 8 :gridwidth 2]
             [(ssc/combobox :id :control-metric
                            :listen [:action handle-control-metric-selection]
                            :model (keys stat-process-fns))
              :grid :wrap :weightx 1.0 :gridx 0 :gridwidth 2]
             [(ssc/label :text "Avg. per" :halign :right)
              :gridx 2 :gridwidth 1]
             [(ssc/text :id :average-interval :columns 4 :text "1")
              :gridx 3 :gridwidth 1]
             [(ssc/label :text "sec") :gridx 4]
             [(ssc/label :text "Cmd. each" :halign :right) :gridx 5]
             [(ssc/text :id :command-interval :columns 4 :text "1")
              :gridx 6 :gridwidth 1]
             [(ssc/label :text "sec") :gridx 7]
             [(make-vlc-start-button) :gridx 8 :gridwidth 2]
             [(ssc/label :id :metric-help-msg
                         :halign :center
                         :text (get metric-help-messages "Attention"))
              :grid :wrap :weightx 1.0 :gridx 0 :gridwidth 10]])]))

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
