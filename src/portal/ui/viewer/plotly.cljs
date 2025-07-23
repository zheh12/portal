(ns portal.ui.viewer.plotly
  "Viewer for plotly
  For more information, see:
  https://github.com/plotly/plotly.js
  https://github.com/plotly/react-plotly.js"
  (:require
   ["react-plotly.js" :refer [default] :rename {default Plot}]
   [clojure.spec.alpha :as sp]
   [cljs.pprint :as pp]))

;; sci in portal doesn't currently support spec
(sp/def ::name string?)
(sp/def ::plotly
  (sp/keys :req-un [::data]
           :opt-un [::name ::layout ::config ::style]))

(defn plotly-viewer [value]
  ^{:key (hash value)}
  [:div {:style {:display :flex :justify-content :flex-end}}
   [:> Plot (merge {:style {:width "100%" :height "100%"}} value)]])

(defn kline-viewer [value]
  (let [{:keys [bar-series upper lower middle name]} value
        pct-change (map #(* 100 (/ (- %2 %1) %1)) (:open bar-series) (:close bar-series))
        hovertext (map #(str "change: " (.toFixed % 2) "%") pct-change)
        candle-data {:name       "candlestick"
                     :x          (:start-time bar-series)
                     :close      (:close bar-series)
                     :decreasing {:line {:color "#7F7F7F"}}
                     :high       (:high bar-series)
                     :increasing {:line {:color "#17BECF"}}
                     :line       {:color "rgba(31,119,180,1)"}
                     :low        (:low bar-series)
                     :open       (:open bar-series)
                     :hovertext  hovertext
                     :type       "candlestick"
                     :xaxis      "x"
                     :yaxis      "y"}
        upper-band {:x    (:start-time bar-series)
                    :y    upper
                    :line {:color "blue"}
                    :name "Upper Band"
                    :type "scatter"
                    :mode "lines"}
        lower-band {:x    (:start-time bar-series)
                    :y    lower
                    :line {:color "red"}
                    :name "Lower Band"
                    :type "scatter"
                    :mode "lines"}
        middle-line {:x    (:start-time bar-series)
                     :y    middle
                     :line {:color "gray" :dash "dot"}
                     :name "SMA (20)"
                     :type "scatter"
                     :mode "lines"}
        layout {:title    {:text (str "Kline for " name)}
                :dragmode "zoom"
                :xaxis    {:rangeslider {:visible true}}
                :yaxis    {:autorange  true
                           :fixedrange false
                           :type       "log"}}
        style {:width "100%" :height "100%"}]
    ^{:key (hash value)}
    [:div {:style {:display :flex :justify-content :flex-end}}
     [:> Plot {:style style
               :data [candle-data upper-band lower-band middle-line]
               :layout layout}]]))

(def viewer
  {:predicate (partial sp/valid? ::plotly)
   :component #'plotly-viewer
   :name :portal.viewer/plotly})

(def kline
  {:predicate true
   :component #'kline-viewer
   :name :portal.viewer/plotly-kline})

(comment
  (tap> {:data [{:x [0 2] :y [0 3]}]
         :style {:width 960 :height 560}})
  (tap> {:data [{:values [0 2 3 4 5]
                 :type :pie}]
         :style {:width "100%" :height "100%"}}))

