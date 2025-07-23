(ns user)

(defn lazy-fn [symbol]
  (fn [& args] (apply (requiring-resolve symbol) args)))

(def start! (lazy-fn 'shadow.cljs.devtools.server/start!))
(def watch  (lazy-fn 'shadow.cljs.devtools.api/watch))
(def repl   (lazy-fn 'shadow.cljs.devtools.api/repl))

(defn cljs
  ([] (cljs :client))
  ([build-id] (start!) (watch build-id) (repl build-id)))

(defn node [] (cljs :node))

(comment
  (require '[portal.api :as p])
  (add-tap #'p/submit)
  (remove-tap #'p/submit)

  (watch :pwa)

  (p/clear)
  (p/close)
  (p/docs {:mode :dev})

  (def portal (p/open {:mode :dev
                       :port 5678}))
  (def dev    (p/open {:mode :dev}))
  (def emacs  (p/open {:mode :dev :launcher :emacs}))
  (def code   (p/open {:mode :dev :launcher :vs-code}))
  (def idea   (p/open {:mode :dev :launcher :intellij}))
  (def work   (p/open {:mode :dev :main 'workspace/app}))
  (def tetris (p/open {:mode :dev :main 'tetris.core/app}))

  (p/repl portal)

  (p/eval-str "(require '[portal.ui.viewer.plotly])")
  (tap>
    ^{:portal.viewer/default :portal.viewer/plotly}
    {:data
     [{:x [1 2 3]
       :y [2 6 3]
       :type "scatter"
       :mode "lines+markers"
       :marker {:color "red"}}
      {:type "bar" :x [1 2 3] :y [2 5 3]}]})

  (p/eval-str "(js/console.info \"Hello, Portal!\")")

  (tap> 1)
  (tap>
    ^{:portal.viewer/default :portal.viewer/highcharts-stock}
    {:title {:text "My Stock chart"}
     :series [{:data [1 2 3 4 5]}]})

  (require '[examples.data :refer [data]])
  (dotimes [_i 25] (tap> data)))