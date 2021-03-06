(ns nl-streamer.core
  (:require [clojure.tools.cli :refer [parse-opts]]
            [cheshire.core :as json]
            [nl-streamer.utils :as u]
            [nl-streamer.serial-port :as sp]
            [nl-streamer.neurosky :as nsky]
            [nl-streamer.ui :as ui]
            [clojure.string :refer [join]]
            [clojure.java.io :refer [as-file]])
  (:gen-class))

(def cli-opts
  [["-p" "--port ID" "ID of the EEG headset port to read from"
    :id :port-id
    :parse-fn #(Integer/parseInt %)
    :validate [integer? "Port ID must be integer"]]
   ["-c" "--config FILENAME" "Configuration file (JSON)"
    :default "config.json"
    :validate [#(.exists (as-file %)) "Configuration file does not exist"]]
   ["-h" "--help"]])

(defn usage [opts-summary]
  (->> ["Stream utility for the Neurolyzer service."
        "It connects to the EEG device and stream its data to the server."
        ""
        "Usage:"
        "  $ nl-streamer [command] [options]"
        ""
        "Options:"
        opts-summary
        ""
        "Commands are:"
        "  ports    - print list of available ports to connect and exit"
        "  neurosky - connect and process data from NeuroSky headsets"
        "  ui - run nl-streamer in UI mode"]
       (join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (join \newline errors)))

(defn show-ports []
  (sp/list-ports)
  (System/exit 0))

(defn start-neurosky! [opts]
  (if-let [port-id (:port-id opts)]
    (nsky/start! (sp/port-at port-id))
    (u/exit 4 "Port of NeuroSky headset is not set!")))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-opts)]
    (cond
      (:help options) (u/exit 0 (usage summary))
      (not= (count arguments) 1) (u/exit 1 (usage summary))
      errors (u/exit 2 (error-msg errors)))
    (case (first arguments)
      "ui" (u/setup-and-start! options ui/show-window! nil)
      "ports" (show-ports)
      "neurosky" (u/setup-and-start! options start-neurosky! nsky/stop!)
      (u/exit 3 (usage summary)))))
