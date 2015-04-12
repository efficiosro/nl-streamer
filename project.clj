(defproject nl-streamer "0.1.0"
  :description "Stream utility to send EEG data to the Neurolyzer service."
  :url "http://efficio.cz"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [org.clojure/tools.logging "0.3.1"]
                 [cheshire "5.4.0"]
                 [rxtx22 "1.0.6"]
                 [clj-http-lite "0.2.1"]]
  :main ^:skip-aot nl-streamer.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
