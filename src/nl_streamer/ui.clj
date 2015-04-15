(ns nl-streamer.ui
  (:require [seesaw.core :as ssc]
            [nl-streamer.serial-port :refer [port-names]]
            [nl-streamer.neurosky :as nsky]))

(def ^:private window (ssc/frame :title "Neurolyzer Streamer"))

(def ^:private button-texts {:disconnected "Connect"
                             :connected "Disconnect"})
(def ^:private label-texts {:disconnected "Not Connected"
                            :connected "Connected"})
(def ^:private combo-enabled {:disconnected true
                              :connected false})

(defn alter-connection! []
  (if (= :disconnected (nsky/get-connection-status))
    (if-let [path (ssc/config (ssc/select window [:#ports]) :text)]
      (nsky/start! path))
    (nsky/stop!)))

(defn reconfig-ui! [status]
  (let [btn (ssc/select window [:#connect])
        lbl (ssc/select window [:#status])
        combo (ssc/select window [:#ports])]
    (ssc/config! btn :text (get button-texts status))
    (ssc/config! lbl :text (get label-texts status))
    (ssc/config! combo :enabled? (get combo-enabled status))))

(defn click-button [_]
  (alter-connection!)
  (reconfig-ui! (nsky/get-connection-status)))

(defn make-connect-button []
  (ssc/button :id :connect
              :text (:disconnected button-texts)
              :listen [:action click-button]))

(defn- make-window-content []
  (ssc/flow-panel
   :items [(ssc/combobox :id :ports :model (port-names))
           :separator
           (ssc/label :id :status :text (:disconnected label-texts))
           (make-connect-button)]))

(defn show-window! [_]
  (-> window
      (ssc/config! :content (make-window-content))
      (ssc/pack!)
      (ssc/show!)))
