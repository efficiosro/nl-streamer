(defproject nl-streamer "0.3.2"
  :description
  "Stream utility to send EEG data to the Neurolyzer service and control VLC."
  :url "http://efficio.cz"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [cheshire "5.5.0"]
                 [rxtx22 "1.0.6"]
                 [clj-http-lite "0.3.0"]
                 [seesaw "1.4.5"]]
  :main ^:skip-aot nl-streamer.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
