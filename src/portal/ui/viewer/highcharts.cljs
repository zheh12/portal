(ns portal.ui.viewer.highcharts
  (:require ["highcharts/highstock" :as Highcharts]
            ["highcharts-react-official" :default HighchartsReact]))

(defn highcharts-stock-viewer [value]
  ^{:key (hash value)}
  [:div {:style {:width "100%" :height "100%"}}
   [:> HighchartsReact
    {:highcharts Highcharts
     :constructor-type "stockChart"
     :options value}]])

(def viewer
  {:predicate (constantly true)
   :component #'highcharts-stock-viewer
   :name :portal.viewer/highcharts-stock})