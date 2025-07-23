(ns portal.ui.viewer.highcharts
  (:require 
   ["highcharts/highstock" :as HighchartsStock]
   ["highcharts/modules/exporting" :default HighchartsExporting]
   ["highcharts-react-official" :default HighchartsReact]))

(js/console.log HighchartsExporting)
(when (fn? HighchartsExporting)
  (HighchartsExporting HighchartsStock))

(defn kline-bollinger-viewer [value]
  (let [{:keys [ohlc volume upper lower middle
                interval width percent-b slope name]} value
        title (str "Kline for " name)
        ohlc-yaxis {:title  {:text "OHLC"}
                    :type   "logarithmic"
                    :resize {:enabled true}
                    :height "50%"}
        slope-yaxis {:title   {:text "Slope"}
                     :resize  {:enabled true}
                     :tooltip {:valueDecimals 1}
                     :top     "50%"
                     :height  "10%"
                     :offset  0}
        band-width-yaxis {:title   {:text "Band Width"}
                          :resize  {:enabled true}
                          :tooltip {:valueDecimals 1}
                          :top     "60%"
                          :height  "10%"
                          :offset  0}
        percent-b-yaxis {:title   {:text "Percent B"}
                         :resize  {:enabled true}
                         :tooltip {:valueDecimals 1}
                         :top     "70%"
                         :height  "15%"
                         :offset  0}
        volume-yaxis {:title  {:text "Volume"}
                      :resize {:enabled true}
                      :top    "85%"
                      :height "15%"
                      :offset 0}
        yaxis [ohlc-yaxis slope-yaxis band-width-yaxis percent-b-yaxis volume-yaxis]
        ohlc-series {:name         "OHLC"
                     :id "kline"
                     :data         ohlc
                     :type         "candlestick"
                     :yAxis        0
                     :groupPadding 0.1
                     :tooltip      {:valueDecimals 2
                                    :pointFormat   "OHLC: {point.open:.2f} - {point.high:.2f} - {point.low:.2f} - {point.close:.2f}"}}
        upper-series {:name      "Upper Band"
                      :data      upper
                      :type      "line"
                      :lineWidth 1
                      :yAxis     0}
        middle-series {:name      "Middle Band (SMA)"
                       :data      middle
                       :type      "line"
                       :lineWidth 1
                       :yAxis     0}
        lower-series {:name      "Lower Band"
                      :data      lower
                      :type      "line"
                      :lineWidth 1
                      :yAxis     0}
        slope-series {:name      "Slope"
                      :data      slope
                      :type      "line"
                      :lineWidth 1
                      :yAxis     1}
        band-width {:name      "Band Width"
                    :data      width
                    :type      "line"
                    :lineWidth 1
                    :yAxis     2
                    :tooltip   {:valueDecimals 1}}
        percent-b {:name      "Percent B"
                   :data      percent-b
                   :type      "line"
                   :lineWidth 1
                   :yAxis     3
                   :tooltip   {:valueDecimals 1}}
        volume-series {:name    "Volume"
                       :data    volume
                       :type    "column"
                       :yAxis   4
                       :tooltip {:valueDecimals 2}}
        series [ohlc-series upper-series middle-series lower-series
                slope-series band-width percent-b volume-series]]
    ^{:key (hash value)}
    [:div {:style {:width "100%" :height "100%"}}
     [:> HighchartsReact
      {:highcharts HighchartsStock
       :constructor-type "stockChart"
       :options {:chart       {:height "1200px"
                               :zooming {:mouseWheel {:enabled false}}}
                 :plotOptions {:series {:dataGrouping {:enabled false}}}
                 :rangeSelector {:enabled false}
                 :tooltip     {:fixed true}
                 :legend      {:enabled true}
                 :title       {:text title}
                 :xAxis       {:range (* 300 interval)
                               :minRange (* 300 interval)
                               :maxRange (* 500 interval)}
                 :yAxis       yaxis
                 :series      series}}]]))

(defn kline-viewer [value]
  (let [{:keys [ohlc volume upper lower middle
                interval width percent-b slope name]} value
        title (str "Kline for " name)
        ohlc-yaxis {:title  {:text "OHLC"}
                    :type   "logarithmic"
                    :resize {:enabled true}
                    :height "70%"}
        volume-yaxis {:title  {:text "Volume"}
                      :resize {:enabled true}
                      :top    "75%"
                      :height "25%"
                      :offset 0}
        yaxis [ohlc-yaxis volume-yaxis]
        ohlc-series {:name         "OHLC"
                     :id "kline"
                     :data         ohlc
                     :type         "candlestick"
                     :yAxis        0
                     :groupPadding 0.1
                     :tooltip      {:valueDecimals 2
                                    :pointFormat   "OHLC: {point.open:.2f} - {point.high:.2f} - {point.low:.2f} - {point.close:.2f}"}}
        volume-series {:name    "Volume"
                       :data    volume
                       :type    "column"
                       :yAxis   1
                       :tooltip {:valueDecimals 2}}
        series [ohlc-series volume-series]]
    ^{:key (hash value)}
    [:div {:style {:width "100%" :height "100%"}}
     [:> HighchartsReact
      {:highcharts HighchartsStock
       :constructor-type "stockChart"
       :options {:chart       {:height "1200px"}
                 :exporting {:enabled true}
                 :rangeSelector {:enabled false}
                 :tooltip     {:fixed true}
                 :legend      {:enabled true}
                 :title       {:text title}
                 :xAxis       {:range (* 300 interval)
                               :minRange (* 300 interval)
                               :maxRange (* 500 interval)}
                 :yAxis       yaxis
                 :series      series}}]]))

(def kline
  {:predicate (constantly true)
   :component #'kline-viewer
   :name :portal.viewer/highcharts-kline})

(def kline-bollinger
  {:predicate (constantly true)
   :component #'kline-bollinger-viewer
   :name :portal.viewer/highcharts-kline-bollinger})