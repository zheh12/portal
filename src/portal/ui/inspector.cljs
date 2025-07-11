(ns portal.ui.inspector
  (:refer-clojure :exclude [coll? map? char?])
  (:require ["anser" :as anser]
            [clojure.set :as set]
            [clojure.string :as str]
            [portal.async :as a]
            [portal.colors :as c]
            [portal.runtime.cson :as cson]
            [portal.runtime.edn :as edn]
            [portal.ui.api :as api]
            [portal.ui.filter :as f]
            [portal.ui.icons :as icons]
            [portal.ui.lazy :as l]
            [portal.ui.react :as react]
            [portal.ui.rpc.runtime :as rt]
            [portal.ui.select :as select]
            [portal.ui.state :as state]
            [portal.ui.styled :as s]
            [portal.ui.theme :as theme]
            [reagent.core :as r])
  (:import [goog.math Long]))

(declare inspector*)
(declare inspector)
(declare preview)

(defn url? [value] (instance? js/URL value))
(defn bin? [value] (instance? js/Uint8Array value))
(defn bigint? [value] (= (type value) js/BigInt))
(defn error? [value] (instance? js/Error value))
(defn char? [value] (instance? cson/Character value))
(defn ratio? [value] (instance? cson/Ratio value))

(defn coll? [value]
  (and (clojure.core/coll? value)
       (not (cson/tagged-value? value))))

(defn map? [value]
  (and (clojure.core/map? value)
       (not (cson/tagged-value? value))))

(defn- long? [value] (instance? Long value))

(defn error->data [ex]
  (merge
   (when-let [data (.-data ex)]
     {:data data})
   {:runtime :portal
    :cause   (.-message ex)
    :via     [{:type    (symbol (.-name (type ex)))
               :message (.-message ex)}]
    :stack   (.-stack ex)}))

(defn- inspect-error [error]
  (let [theme (theme/use-theme)]
    [s/div
     {:style
      {:color         (::c/exception theme)
       :border        [1 :solid (::c/exception theme)]
       :background    (str (::c/exception theme) "22")
       :border-radius (:border-radius theme)}}
     [s/div
      {:style
       {:padding       (:padding theme)
        :border-bottom [1 :solid (::c/exception theme)]}}
      "Rendering error: " (.-message error)]
     [:pre
      {:style {:margin      0
               :box-sizing  :border-box
               :padding     (:padding theme)
               :white-space :pre-wrap}}
      [:code (.-stack error)]]]))

(defn- inspect-error* [error] [:f> inspect-error error])

(def error-boundary
  (r/create-class
   {:display-name "ErrorBoundary"
    :constructor
    (fn [this _props]
      (set! (.-state this) #js {:error nil}))
    :component-did-catch
    (fn [_this _e _info])
    :get-derived-state-from-error
    (fn [error] #js {:error error})
    :render
    (fn [this]
      (if-let [error (.. this -state -error)]
        (r/as-element [inspect-error* error])
        (.. this -props -children)))}))

(defonce viewers api/viewers)

(defn viewers-by-name [viewers]
  (into {} (map (juxt :name identity) viewers)))

(defn- scalar? [value]
  (or (nil? value)
      (boolean? value)
      (number? value)
      (keyword? value)
      (symbol? value)
      (string? value)
      (long? value)
      (url? value)
      (bigint? value)
      (char? value)
      (ratio? value)
      (inst? value)
      (uuid? value)))

(defn- scalar-seq? [value]
  (and (coll? value)
       (seq value)
       (every? scalar? value)))

(defn- get-compatible-viewers-1 [viewers {:keys [value] :as context}]
  (let [by-name        (viewers-by-name viewers)
        default-viewer (get by-name
                            (or (get-in (meta context) [:props :portal.viewer/default])
                                (:portal.viewer/default (meta value))
                                (:portal.viewer/default context)
                                (when (scalar-seq? value)
                                  :portal.viewer/pprint)))
        viewers        (cons default-viewer (remove #(= default-viewer %) viewers))]
    (filter #(when-let [pred (:predicate %)] (pred value)) viewers)))

(defn get-compatible-viewers [viewers contexts]
  (if (nil? contexts)
    (get-compatible-viewers-1 viewers contexts)
    (->> contexts
         (map #(get-compatible-viewers-1 viewers %))
         (map set)
         (apply set/intersection))))

(defn- get-selected-viewer
  ([state context]
   (get-selected-viewer state context (:value context)))
  ([state context value]
   (get-selected-viewer state context (state/get-location context) value))
  ([state context location value]
   (when-let [selected-viewer
              (and (= (:value context) value)
                   @(r/cursor state [:selected-viewers location]))]
     (some #(when (= (:name %) selected-viewer) %) @api/viewers))))

(defn- get-compatible-viewer [context value]
  (first (get-compatible-viewers-1 @api/viewers (assoc context :value value))))

(defn get-viewer
  ([state context]
   (get-viewer state context (:value context)))
  ([state context value]
   (or (get-selected-viewer state context value)
       (get-compatible-viewer context value))))

(defn set-viewer-1 [state context viewer-name]
  (let [location (state/get-location context)]
    (assoc-in state [:selected-viewers location] viewer-name)))

(defn set-viewer [state contexts viewer-name]
  (reduce (fn [state context] (set-viewer-1 state context viewer-name)) state contexts))

(defn set-viewer! [state contexts viewer-name]
  (state/dispatch! state set-viewer contexts viewer-name))

(def ^:private parent-context (react/create-context nil))

(defn- use-parent [] (react/use-context parent-context))

(defn- with-parent [context & children]
  (into [:r> (.-Provider parent-context) #js {:value context}] children))

(def ^:private inspector-context
  (react/create-context {:depth 0 :path [] :stable-path [] :alt-bg false}))

(defn use-context []
  (some->
   (react/use-context inspector-context)
   (vary-meta assoc ::select/position (select/use-position))))

(defn with-depth [& children]
  (let [context (use-context)]
    (into [:r> (.-Provider inspector-context)
           #js {:value (assoc context :depth 0)}] children)))

(defn inc-depth [& children]
  (let [context (use-context)]
    (into [:r> (.-Provider inspector-context)
           #js {:value (update context :depth inc)}]
          children)))

(defn dec-depth [& children]
  (let [context (use-context)]
    (into [:r> (.-Provider inspector-context)
           #js {:value (update context :depth dec)}]
          children)))

(defn with-context [value & children]
  (let [context (use-context)]
    (into
     [:r> (.-Provider inspector-context)
      #js {:value (merge context value)}] children)))

(defn with-default-viewer [viewer & children]
  (into [with-context {:portal.viewer/default viewer}] children))

(defn with-collection [coll & children]
  (into [with-context
         {:key nil
          :collection coll}]
        children))

(defn- get-stable-path
  "Since seqs grow at the front, reverse indexing them will yield a more stable
  path."
  [context k]
  (let [{:keys [collection stable-path] :or {stable-path []}} context]
    (if-not (and collection (seq? collection) (number? k))
      (conj stable-path k)
      (conj stable-path (- (count collection) k 1)))))

(defn with-key [k & children]
  (let [context (use-context)
        path    (get context :path [])]
    (into [with-context
           {:key         k
            :path        (conj path k)
            :stable-path (get-stable-path context k)}]
          children)))

(defn with-readonly [& children]
  (into [with-context {:readonly? true}] children))

(defonce ^:private options-context (react/create-context nil))

(defn use-options [] (react/use-context options-context))

(defn- with-options [options & children]
  (into [:r> (.-Provider options-context) #js {:value options}] children))

(defn get-value-type [value]
  (cond
    (tagged-literal? value)
    :tagged

    (cson/tagged-value? value)
    (:tag value)

    (long? value)     :number
    (bin? value)      :binary
    (map? value)      :map
    (set? value)      :set
    (vector? value)   :vector
    (list? value)     :list
    (coll? value)     :coll
    (boolean? value)  :boolean
    (symbol? value)   :symbol
    (bigint? value)   :bigint
    (number? value)   :number
    (string? value)   :string
    (keyword? value)  :keyword
    (var? value)      "portal/var"
    (error? value)    :error
    (char? value)     :char
    (ratio? value)    :ratio

    (uuid? value)     :uuid
    (url? value)      :uri
    (inst? value)     :inst

    (array? value)    :js-array
    (object? value)   :js-object

    (rt/runtime? value)
    (rt/tag value)))

(defn- child? [location context]
  (let [v   (:stable-path location)
        sub (:stable-path context)
        a (count v)
        b (count sub)]
    (cond
      (> a b) false
      :else   (= v (take a sub)))))

(defn- all-locations [state context]
  (let [search-text @(r/cursor state [:search-text])]
    (seq
     (reduce-kv
      (fn [out location search-text]
        (if-not (child? location context)
          out
          (into
           out
           (keep
            (fn [substring]
              (when-not (str/blank? substring)
                {:substring substring
                 :context (-> location meta :context)}))
            (str/split search-text #"\s+")))))
      []
      search-text))))

(defn- use-search-words []
  (let [state   (state/use-state)
        context (use-context)]
    (when-not (:readonly? context)
      @(r/track all-locations state context))))

(defn toggle-bg [& children]
  (let [context (use-context)]
    (into [with-context (update context :alt-bg not)] children)))

(defn get-background
  ([]
   (get-background (use-context)))
  ([{:keys [alt-bg]}]
   (let [theme (theme/use-theme)]
     (if-not alt-bg
       (::c/background theme)
       (::c/background2 theme)))))

(defn get-background2 []
  (get-background (update (use-context) :alt-bg not)))

(defn- highlight-words* [string]
  (let [theme        (theme/use-theme)
        state        (state/use-state)
        search-words (use-search-words)
        background   (get-background2)]
    (if-let [segments (some->> search-words (f/split string))]
      [l/lazy-seq
       (for [{:keys [context start end]} segments]
         ^{:key start}
         [s/span
          {:style
           (when context
             {:cursor :pointer
              :color background
              :background (get theme (nth theme/order (:depth context)))})
           :style/hover (when context {:text-decoration :underline})
           :on-click
           (fn [e]
             (when context
               (.stopPropagation e)
               (state/dispatch! state state/select-context context)))}
          (subs string start end)])
       {:default-take 5}]
      string)))

(defn highlight-words [string]
  (let [[sensor visible?] (l/use-visible)
        readonly?  (:readonly? (use-context))]
    (cond
      readonly? string
      visible?  [highlight-words* string]
      :else
      [s/span
       {:style {:position :relative}}
       string
       [s/div {:style {:position :absolute :top 2 :left 1}}
        sensor]])))

(defn- ->id [value]
  (str (hash value) (pr-str (type value))))

(defn tabs [value]
  (let [theme   (theme/use-theme)
        options (keys value)
        [option set-option!] (react/use-state (first options))
        background (get-background)]
    [s/div
     {:style
      {:background background
       :border [1 :solid (::c/border theme)]
       :border-radius (:border-radius theme)}}
     [with-readonly
      [s/div
       {:style
        {:display :flex
         :align-items :stretch
         :border-bottom [1 :solid (::c/border theme)]}}
       (for [value options]
         ^{:key (->id value)}
         [s/div
          {:style
           {:flex "1"
            :cursor :pointer
            :border-right
            (if (= value (last options))
              :none
              [1 :solid (::c/border theme)])}
           :on-click
           (fn [e]
             (when-not (= value option)
               (set-option! value)
               (.stopPropagation e)))}
          [s/div
           {:style {:box-sizing :border-box
                    :padding (:padding theme)
                    :border-bottom
                    (if (= value option)
                      [5 :solid (::c/boolean theme)]
                      [5 :solid (::c/border theme)])}}
           [preview value]]])]]
     [s/div
      {:style
       {:box-sizing :border-box
        :padding (:padding theme)}}
      [select/with-position
       {:row 0 :column 0}
       [dec-depth
        [with-key option [inspector (get value option)]]]]]]))

(defn- tagged-value [tag value]
  (let [theme (theme/use-theme)]
    [s/div
     {:style {:position :relative :display :flex :gap (:padding theme)}}
     [s/div
      {:style {:position :sticky :top (:padding theme) :height :fit-content}}
      [s/div
       {:style {:display :flex
                :align-items :center}}
       [s/span {:style {:color (::c/tag theme)}} "#"]
       (when-let [ns (namespace tag)]
         [:<> [highlight-words ns] "/"])
       [highlight-words (name tag)]]]
     [s/div {:style
             {:flex "1"}}
      [with-key
       tag
       [select/with-position {:row 0 :column 0}
        [toggle-bg [inspector value]]]]]]))

(defn toggle-expand [{:keys [style context]}]
  (let [state     (state/use-state)
        context*  (use-context)
        context   (or context context*)
        theme     (theme/use-theme)
        color     (get theme (nth theme/order (:depth context)))
        {:keys [expanded?]} (use-options)]
    (when-not (:readonly? context)
      [s/div
       (merge
        {:title
         (if expanded?
           "Click to collapse value. - SPACE | E"
           "Click to expand value. - SPACE | E")
         :style
         (merge
          {:cursor :pointer
           :display :flex
           :align-items :center
           :color (::c/border theme)}
          style)
         :style/hover {:color color}
         :on-click (fn [e]
                     (.stopPropagation e)
                     (if (.-shiftKey e)
                       (state/dispatch! state state/expand-inc-1 context)
                       (state/dispatch! state state/toggle-expand-1 context)))})
       (if expanded?
         [icons/caret-down]
         [icons/caret-right])])))

(defn- preview-coll [open close]
  (fn [value]
    (let [theme (theme/use-theme)]
      [s/div
       {:style {:display :flex}}
       [toggle-expand {:style {:padding-right (:padding theme)}}]
       [s/div
        {:style
         {:color (::c/diff-remove theme)}}
        open close [:sub (count value)]]])))

(def ^:private preview-map    (preview-coll "{" "}"))
(def ^:private preview-vector (preview-coll "[" "]"))
(def ^:private preview-list   (preview-coll "(" ")"))
(def ^:private preview-set    (preview-coll "#{" "}"))

(defn- coll-action [props]
  (let [theme (theme/use-theme)]
    [s/div
     {:style {:border-right [1 :solid (::c/border theme)]}}
     [s/div
      {:on-click (:on-click props)
       :style/hover {:color (::c/tag theme)}
       :style {:cursor :pointer
               :user-select :none
               :color (::c/namespace theme)
               :box-sizing :border-box
               :padding (:padding theme)
               :font-size  (:font-size theme)
               :font-family (:font-family theme)}}
      (:title props)]]))

(defn- collection-header [values]
  (let [[show-meta? set-show-meta!] (react/use-state false)
        theme    (theme/use-theme)
        metadata (dissoc
                  (meta values)
                  :portal.runtime/id
                  :portal.runtime/type)]
    [s/div
     {:style
      {:border [1 :solid (::c/border theme)]
       :background (get-background)
       :border-top-left-radius (:border-radius theme)
       :border-top-right-radius (:border-radius theme)
       :border-bottom-right-radius 0
       :border-bottom-left-radius 0
       :border-bottom :none}}
     [s/div
      {:style
       {:display :flex
        :align-items :stretch}}
      [s/div
       {:style
        {:display :inline-block
         :box-sizing :border-box
         :padding (:padding theme)
         :border-right [1 :solid (::c/border theme)]}}
       [preview values]]

      (when-let [ns (:map-ns (use-options))]
        [s/div {:title "Namespaced map."
                :style
                {:box-sizing :border-box
                 :padding (:padding theme)
                 :display :flex
                 :border-right [1 :solid (::c/border theme)]}}
         [s/span {:style {:color (::c/tag theme)}} "#"]
         [theme/with-theme+
          {::c/keyword (::c/namespace theme)}
          [select/with-position {:row -2 :column 0}
           [with-key 'ns [inspector ns]]]]])

      (when (seq metadata)
        [coll-action
         {:on-click
          (fn [e]
            (set-show-meta! not)
            (.stopPropagation e))
          :title "metadata"}])

      (when-let [type (-> values meta :portal.runtime/type)]
        [s/div {:title "Value type."
                :style
                {:box-sizing :border-box
                 :padding (:padding theme)
                 :border-right [1 :solid (::c/border theme)]}}
         [select/with-position {:row -2 :column 1} [inspector type]]])]
     (when show-meta?
       [s/div
        {:style
         {:border-top [1 :solid (::c/border theme)]
          :box-sizing :border-box
          :padding (:padding theme)}}
        [with-depth
         [select/with-position {:row -1 :column 0} [inspector metadata]]]])]))

(defn- container-map-k [child]
  [s/div {:style
          {:grid-column "1"
           :display :flex
           :align-items :flex-start}}
   [s/div {:style
           {:width "100%"
            :top (:padding (theme/use-theme))
            :position :sticky}}
    child]])

(defn- container-map-v [child]
  [s/div {:style
          {:grid-column "2"
           :display :flex
           :align-items :flex-start}}
   [s/div {:style
           {:width "100%"
            :top (:padding (theme/use-theme))
            :position :sticky}}
    child]])

(defn try-sort [values]
  (if (sorted? values)
    values
    (try (sort values)
         (catch :default _e values))))

(defn try-sort-map [values]
  (if (sorted? values)
    values
    (try (sort-by first values)
         (catch :default _e values))))

(defn use-sort-map [values]
  (react/use-memo #js [values] (try-sort-map values)))

(defn- container-map [child]
  (let [theme (theme/use-theme)]
    [s/div
     {:style
      {:width "100%"
       :display :grid
       :grid-template-columns "auto 1fr"
       :background (get-background)
       :grid-gap (:padding theme)
       :padding (:padding theme)
       :box-sizing :border-box
       :color (::c/text theme)
       :font-size  (:font-size theme)
       :border-bottom-left-radius (:border-radius theme)
       :border-bottom-right-radius (:border-radius theme)
       :border-top-right-radius 0
       :border-top-left-radius 0
       :border [1 :solid (::c/border theme)]}}
     child]))

(defn get-props [value k]
  (let [m (meta value)]
    (when-let [viewer (get-in m [:portal.viewer/for k])]
      (merge
       (select-keys m [viewer])
       {:portal.viewer/default viewer}))))

(defn use-search-text []
  (let [state       (state/use-state)
        context     (use-context)
        location    (state/get-location context)]
    @(r/cursor state [:search-text location])))

(defn- inspect-map-k-v* [map-ns search-text values]
  (let [matcher       (f/match search-text)
        sorted-values (use-sort-map values)]
    ^{:key search-text}
    [container-map
     [l/lazy-seq
      (keep-indexed
       (fn [index [k v]]
         (when (or (matcher k) (matcher v))
           ^{:key (str (->id k) (->id v))}
           [:<>
            [select/with-position
             {:row index :column 0}
             [with-context
              {:key? true}
              [container-map-k
               [inspector
                {:map-ns map-ns}
                k]]]]
            [select/with-position
             {:row index :column 1}
             [with-key k
              [container-map-v
               [inspector (get-props values k) v]]]]]))
       sorted-values)]]))

(defn inspect-map-k-v [values]
  (let [map-ns (:map-ns (use-options))]
    [inspect-map-k-v* map-ns (use-search-text) values]))

(defn- get-namespaces [value]
  (when (map? value)
    (into #{}
          (map (fn [k]
                 (when (keyword? k)
                   (some-> (namespace k) keyword))))
          (keys value))))

(defn- namespaced-map [value]
  (let [namespaces (get-namespaces value)]
    (when (= 1 (count namespaces)) (first namespaces))))

(defn- inspect-map [values]
  (let [map-ns  (namespaced-map values)
        options (use-options)]
    [with-options
     (assoc options :map-ns map-ns)
     [with-collection
      values
      [:<>
       ^{:key "header"} [collection-header values]
       ^{:key "values"} [inspect-map-k-v values]]]]))

(defn- container-coll [values child]
  (let [theme (theme/use-theme)]
    [with-collection
     values
     [s/div
      [collection-header values]
      [s/div
       {:style
        {:width "100%"
         :text-align :left
         :display :grid
         :background (get-background)
         :grid-gap (:padding theme)
         :padding (:padding theme)
         :box-sizing :border-box
         :color (::c/text theme)
         :font-size  (:font-size theme)
         :border-bottom-left-radius (:border-radius theme)
         :border-bottom-right-radius (:border-radius theme)
         :border [1 :solid (::c/border theme)]}}
       child]]]))

(defn- inspect-coll* [search-text values]
  (let [n (count values)
        matcher     (f/match search-text)]
    ^{:key search-text}
    [container-coll
     values
     [l/lazy-seq
      (keep-indexed
       (fn [index value]
         (when (matcher value)
           (let [key (str (if (vector? values)
                            index
                            (- n index 1))
                          (->id value))]
             ^{:key key}
             [select/with-position
              {:row index :column 0}
              [with-key index [inspector value]]])))
       values)]]))

(defn- inspect-coll [values]
  (let [search-text (use-search-text)]
    [inspect-coll* search-text values]))

(defn- inspect-js-array [value]
  (let [v (into [] value)]
    [with-collection v [tagged-value 'js v]]))

(defn- ->map [entries]
  (persistent!
   (reduce
    (fn [m entry]
      (let [k (aget entry 0)]
        (if (str/starts-with? k "closure_uid")
          m
          (assoc! m (keyword k) (aget entry 1)))))
    (transient {})
    entries)))

(defn- inspect-js-object [value]
  (let [v (->map (.entries js/Object value))]
    [with-collection v [tagged-value 'js v]]))

(defn- trim-string [string limit]
  (if-not (> (count string) limit)
    string
    (str (subs string 0 limit) "...")))

(defn- inspect-number [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/number theme)}}
     [highlight-words
      (cond
        (cson/is-finite? value) (str value)
        (long? value)           (str value)
        (cson/nan? value)       "##NaN"
        (cson/inf? value)       "##Inf"
        (cson/-inf? value)      "##-Inf")]]))

(defn- inspect-bigint [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/number theme)}}
     [highlight-words (str value "N")]]))

(defn- inspect-char [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/string theme)}}
     [highlight-words (pr-str value)]]))

(defn- inspect-ratio [^js value]
  (let [theme (theme/use-theme)]
    [with-collection
     value
     [s/div
      {:style {:display :flex}}
      [with-key
       'numerator
       [select/with-position
        {:row 0 :column 0}
        [s/div [inspector (.-numerator value)]]]]
      [s/span {:style {:color (::c/number theme)}} "/"]
      [with-key
       'denominator
       [select/with-position
        {:row 0 :column 1}
        [s/div [inspector (.-denominator value)]]]]]]))

(defn- url-string? [string]
  (re-matches #"https?://.*" string))

(defn- inspect-string [value]
  (let [theme     (theme/use-theme)
        limit     (:string-length theme)
        context   (use-context)
        expanded? (:expanded? (use-options))]
    (cond
      (url-string? value)
      [s/span
       {:style {:color (::c/string theme)}}
       "\""
       [s/a
        {:href   value
         :target "_blank"
         :style  {:color (::c/string theme)}}
        [highlight-words (trim-string value limit)]]
       "\""]

      (or (< (count value) limit)
          (= (:depth context) 1)
          expanded?)
      [s/span {:style {:white-space :pre-wrap :color (::c/string theme)}}
       [highlight-words (pr-str value)]]

      :else
      [s/span {:style {:white-space :pre-wrap :color (::c/string theme)}}
       [highlight-words (pr-str (trim-string value limit))]])))

(defn- inspect-namespace [value]
  (let [theme (theme/use-theme)]
    (when-let [ns (namespace value)]
      [s/span
       [s/span {:style {:color (::c/namespace theme)}}
        [highlight-words ns]]
       [s/span {:style {:color (::c/text theme)}} "/"]])))

(defn- inspect-boolean [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/boolean theme)}}
     [highlight-words (pr-str value)]]))

(defn- inspect-symbol [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/symbol theme) :white-space :nowrap}}
     [inspect-namespace value]
     [highlight-words (name value)]]))

(defn- inspect-keyword [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/keyword theme) :white-space :nowrap}}
     ":"
     (when-not (:map-ns (:props (use-options)))
       [inspect-namespace value])
     [highlight-words (name value)]]))

(defn- inspect-inst [value]
  [tagged-value 'inst (.toJSON value)])

(defn- inspect-uuid [value]
  [tagged-value 'uuid (str value)])

(defn- get-var-symbol [value]
  (if-let [rep (:rep value)]
    rep
    (let [m (meta value)]
      (symbol (name (:ns m)) (name (:name m))))))

(defn- inspect-var [value]
  (let [theme (theme/use-theme)]
    [s/span
     [s/span {:style {:color (::c/tag theme)}} "#'"]
     [inspect-symbol (get-var-symbol value)]]))

(defn- inspect-regex [value]
  (let [theme (theme/use-theme)]
    [s/div
     {:style
      {:display :flex}}
     [s/span {:style {:color (::c/tag theme)}} "#"]
     [select/with-position
      {:column 0 :row 0}
      [with-key :s [inspector (:rep value)]]]]))

(defn- inspect-uri [value]
  (let [theme (theme/use-theme)
        value (str value)]
    [s/a
     {:href value
      :style {:color (::c/uri theme)}
      :target "_blank"}
     value]))

(defn- inspect-tagged [value]
  [tagged-value (:tag value) (:form value)])

(defn- inspect-ansi [string]
  (let [theme (theme/use-theme)]
    (try
      [:pre
       {:style
        {:margin      0
         :white-space :pre-wrap
         :font-size   (:font-size theme)
         :font-family (:font-family theme)}
        :dangerouslySetInnerHTML
        {:__html (anser/ansiToHtml string)}}]
      (catch :default e
        (.error js/console e)
        string))))

(declare inspect-object)
(declare get-inspect-component)

(defn- inspect-unreadable [value]
  (let [theme     (theme/use-theme)
        limit     (:string-length theme)
        context   (use-context)
        expanded? (:expanded? (use-options))]
    [s/span {:style
             {:color (::c/text theme)}}
     [inspect-ansi
      (if (or (< (count value) limit)
              (= (:depth context) 1)
              expanded?)
        value
        (trim-string value limit))]]))

(defn- inspect-object* [string]
  (let [context (use-context)]
    (try
      (let [v (edn/read-string string)]
        (cond
          (nil? v) [highlight-words "nil"]

          (= inspect-object
             (get-inspect-component
              (get-value-type v)))
          [inspect-unreadable string]

          :else [inspector* context v]))
      (catch :default _
        [inspect-unreadable string]))))

(defn- inspect-object [value] [inspect-object* (pr-str value)])

(defn- inspect-remote [value] [inspect-object* (:rep value)])

(defn- get-preview-component [type]
  (case type
    :map        preview-map
    :set        preview-set
    :vector     preview-vector
    :js-array   inspect-js-array
    :js-object  inspect-js-object
    :list       preview-list
    :coll       preview-list
    :boolean    inspect-boolean
    :symbol     inspect-symbol
    :number     inspect-number
    :bigint     inspect-bigint
    :string     inspect-string
    :keyword    inspect-keyword
    :inst       inspect-inst
    :uuid       inspect-uuid
    "portal/var" inspect-var
    "portal/re" inspect-regex
    "remote"    inspect-remote
    :char       inspect-char
    :ratio      inspect-ratio
    :uri        inspect-uri
    :tagged     inspect-tagged
    :error      inspect-error
    inspect-object))

(defn preview [value]
  (let [type      (get-value-type value)
        component (get-preview-component type)]
    [component value]))

(defn- get-inspect-component [type]
  (case type
    (:set :vector :list :coll) inspect-coll
    :js-array   inspect-js-array
    :js-object  inspect-js-object
    :map        inspect-map
    :boolean    inspect-boolean
    :symbol     inspect-symbol
    :number     inspect-number
    :bigint     inspect-bigint
    :string     inspect-string
    :keyword    inspect-keyword
    :inst       inspect-inst
    :uuid       inspect-uuid
    "portal/var" inspect-var
    "portal/re" inspect-regex
    "remote"    inspect-remote
    :char       inspect-char
    :ratio      inspect-ratio
    :uri        inspect-uri
    :tagged     inspect-tagged
    :error      inspect-error
    inspect-object))

(defn- default-expand? [state theme context value]
  (let [depth   (:depth context)
        viewer  (get-viewer state context value)]
    (or (= depth 1)
        (= (:name viewer) :portal.viewer/tree)
        (and (coll? value)
             (= (:name viewer) :portal.viewer/inspector)
             (<= depth (:max-depth theme))))))

(defn- get-info [state context location value]
  {:expanded? @(r/track state/expanded? state context)
   :selected  @(r/track state/selected state context)
   :viewer    (or @(r/track get-selected-viewer state context location value)
                  (get-compatible-viewer context value))})

(defn use-wrapper-options [context]
  (let [state          (state/use-state)
        location       (state/get-location context)
        {:keys [viewer selected]} (use-options)]
    (when-not (:readonly? context)
      {:on-mouse-down
       (fn [e]
         (.stopPropagation e)
         (when (= (.-button e) 1)
           (state/dispatch! state state/toggle-expand location)))
       :on-click
       (fn [e]
         (.stopPropagation e)
         (a/do
           (set-viewer! state [context] (:name viewer))
           (state/dispatch!
            state
            (if selected
              state/deselect-context
              state/select-context)
            context
            (or (.-metaKey e) (.-altKey e)))))
       :on-double-click
       (fn [e]
         (.stopPropagation e)
         (a/do
           (set-viewer! state [context] (:name viewer))
           (state/dispatch! state state/select-context context)
           (state/dispatch! state state/nav context)))})))

(defonce ^:no-doc hover? (r/atom nil))

(defn- multi-select-counter [context]
  (let [theme      (theme/use-theme)
        selected   (:selected (use-options))
        state      (state/use-state)
        background (get-background)
        multi?     (when selected
                     @(r/track #(> (count @(r/cursor state [:selected])) 1)))]
    (when multi?
      [s/div {:style
              {:position :absolute
               :font-size "0.8rem"
               :color background
               :border-radius 100
               :top "-0.5rem"
               :right "-0.5rem"
               :min-width "1rem"
               :height "1rem"
               :z-index 3
               :display :flex
               :align-items :center
               :justify-content :center
               :background (get theme (nth theme/order (:depth context)))}}
       selected])))

(defn- inspector-border [context]
  (let [theme    (theme/use-theme)
        selected (:selected (use-options))
        hover    (and (not (:readonly? context))
                      @(r/track #(= % @hover?) context))
        color    (get theme (nth theme/order (:depth context)))
        transition "border 0.35s, background 0.35s, box-shadow 0.35s, border-radius 0.35s"]
    [:<>
     [s/div
      {:style
       (merge
        {:position :absolute
         :pointer-events :none
         :top 0
         :left 0
         :right 0
         :bottom 0
         :z-index 2
         :transition transition}
        (when (and selected (not hover))
          {:box-shadow [0 0 3 color]})
        (cond
          (and selected hover)
          {:border-top-right-radius (:border-radius theme)
           :border-bottom-right-radius (:border-radius theme)
           :border [1 :solid color]}
          selected
          {:border-radius (:border-radius theme)
           :border [1 :solid color]}
          :else
          {:border-radius (:border-radius theme)
           :border [1 :solid "rgba(0,0,0,0)"]}))}]
     [s/div
      {:style
       (merge
        {:position :absolute
         :pointer-events :none
         :z-index 2
         :left (- (dec (:padding theme)))
         :top 0
         :bottom 0
         :border-radius (:border-radius theme)
         :transition transition}
        (when selected
          {:right 0
           :top 0
           :bottom 0})
        (when-not selected
          {:opacity 0.85})
        (when (and selected hover)
          {:box-shadow [0 0 3 (get theme (nth theme/order (:depth context)))]})
        (if-not hover
          {:border-left [(- (:padding theme) 1) :solid "rgba(0,0,0,0)"]}
          {:border-left [(- (:padding theme) 1) :solid  color]}))}]]))

(defn wrapper [context & children]
  (let [theme          (theme/use-theme)
        {:keys [ref value selected]} (use-options)
        wrapper-options (use-wrapper-options context)
        background (get-background context)]
    (into
     [s/div
      (merge
       wrapper-options
       {:ref   ref
        :title (-> value meta :doc)
        :on-mouse-over
        (fn [e]
          (.stopPropagation e)
          (reset! hover? context))
        :style
        {:position      :relative
         :z-index       0
         :flex          "1"
         :font-family   (:font-family theme)
         :border        [1 :solid "rgba(0,0,0,0)"]
         :background    (when selected background)}})
      ^{:key "inspector-border"} [inspector-border context]
      ^{:key "multi-select-counter"} [multi-select-counter context]]
     children)))

(defn- inspector* [context value]
  (let [ref            (react/use-ref)
        props          (:props (meta context))
        state          (state/use-state)
        location       (state/get-location context)
        theme          (theme/use-theme)
        {:keys [viewer selected expanded?] :as options}
        (get-info state context location value)
        options        (assoc options :ref ref :props props)
        type           (get-value-type value)
        component      (or
                        (when-not (= (:name viewer) :portal.viewer/inspector)
                          (:component viewer))
                        (if expanded?
                          (get-inspect-component type)
                          (get-preview-component type)))]
    (select/use-register-context context viewer)
    (react/use-effect
     #js [(hash location) (some? expanded?)]
     (when (and (nil? expanded?)
                (default-expand? state theme context value))
       (state/dispatch!
        state assoc-in [:expanded? location]
        (get-in (meta value) [:portal.viewer/inspector :expanded] 1))))
    (react/use-effect
     #js [selected (.-current ref)]
     (when (and selected
                (not= (.. js/document -activeElement -tagName) "INPUT"))
       (when-let [el (.-current ref)]
         (when-not (and (.hasFocus js/document) (l/element-visible? el))
           (.scrollIntoView el #js {:inline "nearest" :block "nearest" :behavior "smooth"})))))
    [:> error-boundary
     [with-options options
      [(get-in props [:portal.viewer/inspector :wrapper] wrapper)
       context [component value]]]]))

(defn- tab-index [context]
  (let [ref      (react/use-ref)
        state    (state/use-state)
        selected @(r/track state/selected state context)]
    (react/use-effect
     #js [selected (.-current ref)]
     (when (and selected (.hasFocus js/document))
       (some-> ref .-current (.focus #js {:preventScroll true}))))
    (when-not (:readonly? context)
      [s/div
       {:ref         ref
        :tab-index   0
        :style/focus {:outline :none}
        :style       {:position :absolute}
        :on-focus
        (fn [e]
          (.stopPropagation e)
          (when-not selected
            (state/dispatch! state state/select-context context false)))}])))

(defn inspector
  ([value]
   (inspector nil value))
  ([props value]
   (let [context
         (cond->
          (-> (use-context)
              (assoc :value value)
              (update :depth inc)
              (assoc :parent (use-parent)))
           (get-in props [:portal.viewer/inspector :toggle-bg] true)
           (update :alt-bg not)
           props
           (vary-meta assoc :props props)
           (nil? props)
           (vary-meta dissoc :props props))]
     [with-parent
      context
      ^{:key "tab-index"} [tab-index context]
      [with-context context [inspector* context value]]])))

(def viewer
  {:predicate (constantly true)
   :component #'inspector
   :name :portal.viewer/inspector})
