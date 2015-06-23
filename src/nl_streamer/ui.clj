(ns nl-streamer.ui
  (:require [clojure.data :refer (diff)]
            [seesaw.core :as ssc]
            [seesaw.graphics :as ssg]
            [seesaw.border :refer (line-border to-border)]
            [seesaw.color :refer (color)]
            [nl-streamer.serial-port :refer (port-names)]
            [nl-streamer.neurosky :as nsky]
            [nl-streamer.utils :as u]
            [nl-streamer.vlc :as vlc]))

(def ^:private window (ssc/frame :title "Neurolyzer Streamer" :width 500))

(def ^:private status-config
  {:disconnected {:connect-button-text "Connect"
                  :status-label-text "Not Connected"
                  :device-combo-enabled? true}
   :connected {:connect-button-text "Disconnect"
               :status-label-text "Connected"
               :device-combo-enabled? false}})

(declare update-metric-bars!)

(def ^:private backend->properties
  {"Neurolyzer"
   {:nl? true
    :vlc? false
    :consume-fn (fn [_ stat]
                  (update-metric-bars! stat)
                  (u/send-stat-to-neurolyzer stat))}
   "VLC"
   {:nl? false
    :vlc? true
    :consume-fn (fn [rules stat]
                  (update-metric-bars! stat)
                  (vlc/send-play-command rules stat))}
   "NL + VLC"
   {:nl? true
    :vlc? true
    :consume-fn (fn [rules stat]
                  (update-metric-bars! stat)
                  (vlc/send-play-command rules stat)
                  (u/send-stat-to-neurolyzer stat))}})

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

(defn- get-backends-properties []
  (get backend->properties
       (ssc/config (ssc/select window [:#interfaces]) :text)
       {}))

(defn- update-metric-bars!
  "Updates attention and meditation level bars and numeric representation."
  [stat]
  (let [attention (:attention stat)
        meditation (:meditation stat)
        draw-metric-level
        (fn [grad-colors lvl c g]
          (let [width (ssc/width c)
                w (* width (/ lvl 100))
                h (ssc/height c)
                style {:background (ssg/linear-gradient
                                    :end [width 0]
                                    :colors grad-colors)}]
            (ssg/draw g (ssg/rect 0 0 w h) style)))]
    (ssc/config!
     (ssc/select window [:#meditation-bar])
     :paint (partial draw-metric-level ["#DFDFFF" "#0000FF"] meditation))
    (ssc/config! (ssc/select window [:#meditation-level]) :text meditation)
    (ssc/config!
     (ssc/select window [:#attention-bar])
     :paint (partial draw-metric-level ["#FFDFDF" "#FF0000"] attention))
    (ssc/config! (ssc/select window [:#attention-level]) :text attention)))

(defn alter-connection! [consume-fn]
  (if (= :disconnected (nsky/get-connection-status))
    (let [path (ssc/config (ssc/select window [:#ports]) :text)]
      (when path (nsky/start! path consume-fn)))
    (nsky/stop!)))

(defn reconfig-ui! [btn status]
  (let [conf (get status-config status {})
        lbl (ssc/select window [:#status])
        combo (ssc/select window [:#ports])]
    (ssc/config! btn :text (:connect-button-text conf))
    (ssc/config! lbl :text (:status-label-text conf))
    (ssc/config! combo :enabled? (:device-combo-enabled? conf))))

(defn read-nl-configuration []
  (letfn [(select-all [keys->ids]
            (reduce-kv
             (fn [r k id]
               (assoc r k (ssc/config (ssc/select window [id]) :text)))
             {}
             keys->ids))]
    (select-all {:protocol :#nl-protocol
                 :host :#nl-host
                 :profile-id :#nl-profile-id
                 :exercise-id :#nl-exercise-id
                 :token :#nl-token})))

(defn- make-neurolyzer-panel []
  (let [conf (:config u/*options*)]
    (ssc/form-panel
     :id :neurolyzer-panel
     :border (to-border "Neurolyzer configuration")
     :items [[nil :fill :horizontal :insets (java.awt.Insets. 2 5 0 5)
              :gridx 0 :gridy 0]
             [(ssc/label :text "Protocol" :halign :right)
              :gridwidth 2 :weightx 1.0]
             [(ssc/combobox :id :nl-protocol :model ["HTTP" "HTTPS"])
              :gridx 2]
             [(ssc/label :text "Host" :halign :right)
              :gridx 4]
             [(ssc/text :id :nl-host :columns 17 :text (get conf :host ""))
              :gridx 6 :gridwidth 4]
             [(ssc/label :text "Profile ID" :halign :right)
              :gridx 10 :gridwidth 2]
             [(ssc/text :id :nl-profile-id
                        :columns 3
                        :text (get conf :profile-id ""))
              :gridx 12 :fill :horizontal]
             [(ssc/label :text "Exercise ID" :halign :right)
              :gridx 14]
             [(ssc/text :id :nl-exercise-id
                        :columns 3
                        :text (get conf :exercise-id ""))
              :gridx 16]
             [(ssc/label :text "Token" :halign :right)
              :grid :wrap :gridx 0 :gridwidth 2 :weightx 1.0]
             [(ssc/text :id :nl-token :text (get conf :token ""))
              :gridx 2 :gridwidth 8]
             [(ssc/label :id :status
                         :text (:status-label-text
                                (:disconnected status-config))
                         :halign :center)
              :gridx 10 :gridwidth 8]])))

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
    (vlc/set-playlist! new-plist-items))
  (ssc/pack! window))

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

(defn- handle-control-metric-selection [evt]
  (let [metric (ssc/config (.getSource evt) :text)
        help-label (ssc/select window [:#metric-help-msg])]
    (ssc/config! help-label :text (get metric-help-messages metric))))

(defn- make-vlc-panel []
  (ssc/vertical-panel
   :id :vlc-panel
   :visible? false
   :border (to-border "VLC configuration")
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
             [(ssc/label :halign :right :text "Metric")
              :grid :wrap :gridwidth 1]
             [(ssc/combobox :id :control-metric
                            :listen [:action handle-control-metric-selection]
                            :model (keys stat-process-fns))
              :gridx 1 :gridwidth 3 :weightx 1.0]
             [(ssc/label :text "Avg. per" :halign :right)
              :gridx 4 :gridwidth 1]
             [(ssc/text :id :average-interval :columns 4 :text "1")
              :gridx 5 :gridwidth 1]
             [(ssc/label :text "sec")
              :gridx 6 :gridwidth 1]
             [(ssc/label :text "Cmd. each" :halign :right)
              :gridx 7 :gridwidth 1]
             [(ssc/text :id :command-interval :columns 4 :text "1")
              :gridx 8 :gridwidth 1]
             [(ssc/label :text "sec")
              :gridx 9 :gridwidth 1]
             [(ssc/label :id :metric-help-msg
                         :halign :center
                         :text (get metric-help-messages "Attention"))
              :grid :wrap :weightx 1.0 :gridx 0 :gridwidth 10]])]))

(defn- handle-backend-selection [evt]
  (let [bknd (ssc/text (.getSource evt))
        neurolyzer-panel (ssc/select window [:#neurolyzer-panel])
        vlc-panel (ssc/select window [:#vlc-panel])
        {:keys [nl? vlc?]} (get-backends-properties)]
    (ssc/config! neurolyzer-panel :visible? nl?)
    (ssc/config! vlc-panel :visible? vlc?)
    (ssc/pack! window)))

(defn- handle-connect-button-click [evt]
  (update-metric-bars! {:attention 0, :meditation 0})
  (let [{:keys [nl? vlc? consume-fn]} (get-backends-properties)
        vlc-rules (when vlc? (get-vlc-rules))
        consume-stat-fn (partial consume-fn vlc-rules)]
    (when nl?
      (u/set-options! (u/config->options (read-nl-configuration))))
    (when vlc? (update-vlc-config!))
    (alter-connection! consume-stat-fn)
    (reconfig-ui! (.getSource evt) (nsky/get-connection-status))))

(defn- make-main-panel []
  (ssc/vertical-panel
   :items
   [(ssc/form-panel
     :items [[nil :fill :horizontal :insets (java.awt.Insets. 2 5 0 5)
              :gridx 0 :gridy 0]
             [(ssc/label :halign :right :text "Device")
              :gridwidth 2 :weightx 1/8]
             [(ssc/combobox :id :ports :model (port-names))
              :gridx 2 :weightx 3/8 :gridwidth 6]
             [(ssc/label :halign :right :text "Backend")
              :gridx 8 :gridwidth 2 :weightx 1/8]
             [(ssc/combobox :id :interfaces
                            :listen [:action handle-backend-selection]
                            :model (keys backend->properties))
              :gridx 10 :gridwidth 3 :weightx 3/16]
             [(ssc/button :id :connect
                          :text "Connect"
                          :listen [:action handle-connect-button-click])
              :gridx 13 :gridwidth 3 :weightx 3/16]])
    (ssc/form-panel
     :items [[nil :fill :horizontal :insets (java.awt.Insets. 2 5 0 5)
              :gridx 0 :gridy 0]
             [(ssc/label :text "Meditation") :weightx 0.1]
             [(ssc/label :id :meditation-bar :border (line-border) :text " ")
              :grid :next :gridwidth 4 :weightx 0.85]
             [(ssc/label :id :meditation-level :halign :center :text "0")
              :gridx 5 :gridwidth 1 :weightx 0.05]
             [(ssc/label :text "Attention") :grid :wrap :weightx 0.15]
             [(ssc/label :id :attention-bar :border (line-border) :text " ")
              :grid :next :gridwidth 4 :weightx 0.85]
             [(ssc/label :id :attention-level :halign :center :text "0")
              :gridx 5 :gridwidth 1 :weightx 0.05]])]))

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
