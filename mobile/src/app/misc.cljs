(ns app.misc
  (:require
   ["react-native" :as rn]
   ["react-native-paper" :as paper]
   ["color" :as color]
   ["react-native-gesture-handler" :as g]
   [clojure.string :refer [join split]]
   [applied-science.js-interop :as j]
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [com.rpl.specter :as sp :refer [setval select transform]]
   [tick.alpha.api :as t]
   [re-frame.core :refer [subscribe dispatch dispatch-sync]]
   [potpuri.core :as p]))

(def <sub (comp deref subscribe))

(def >evt dispatch)

(def >evt-sync dispatch-sync)

;; TODO @deprecated
(defn style-sheet [s]
  ^js (-> s
          (#(cske/transform-keys csk/->camelCase %))
          (clj->js)
          (rn/StyleSheet.create)))

(defn get-theme [k] (j/get paper k))

(defn make-session-ish-interval
  [x]
  (let [start (or (:session/start x) (:session-template/start x))
        stop  (or (:session/stop x) (:session-template/stop x))]
    (merge x {:tick/beginning start
              :tick/end       stop})))

(defn touches
  ;; TODO add question mark
  "Works on both sessions and session-templates.
  Merges in `:tick/beginning` and `:tick/end` to make it a valid interval."
  [a b]
  (if (and (some? a)
           (some? b))
    (not (some? (some #{:precedes :preceded-by} ;; checks that these are not the relation
                      [(t/relation (make-session-ish-interval a)
                                   (make-session-ish-interval b))])))
    false))

(defn chance [p]
  (let [r (rand)]
    (case p
      :low  (-> r (< 0.25))
      :med  (-> r (< 0.51))
      :high (-> r (< 0.90))
      (-> r (< 0.75)))))

(defn prepend-zero [n]
  (if (= 1 (count (str n)))
    (str 0 n)
    (str n)))

(def clear-datetime-picker [:set-date-time-picker
                            #:date-time-picker
                            {:value               nil
                             :mode                nil
                             :session-id          nil
                             :session-template-id nil
                             :field-key           nil
                             :visible             false}] )

(defn drop-keyword-sections [n k]
  (-> k
      str
      (split ".")
      (->> (drop n))
      join
      keyword))

(defn hex-if-some
  [color-object]
  (when (some? color-object)
    (-> color-object (j/call :hex))))

(defn make-color-if-some
  [hex]
  (when (some? hex)
    (-> hex color)))

(defn is-color?
  [c]
  (if (some? c)
    (j/contains? c :color)
    false))

(defn active-gesture? [evt]
  (-> evt
      (j/get :nativeEvent)
      (j/get :state)
      (= (j/get g/State :ACTIVE))))

(defn time-label [instant-or-time]
  (str (prepend-zero (t/hour instant-or-time))
       "-"
       (prepend-zero (t/minute instant-or-time))))

(defn native-event->time
  [{:keys [event zoom]}]
  (-> event
      (j/get :y)
      (/ zoom)
      (int)
      (t/new-duration :minutes)
      (->> (t/+ (t/time "00:00")))))

(defn native-event->type
  [{:keys [event width]}]
  (let [right-side? (-> event
                        (j/get :x)
                        (> (-> width (/ 2))))]
    (if right-side?
      :session/track
      :session/plan)))

(defn combine-tag-labels
  [tags]
  (->> tags
       (mapv :tag/label)
       (join " ")))

(defn session-minutes
  [{:session/keys [start stop]}]
  (-> (t/between start stop)
      (t/minutes)))

(defn sessions->min-col
  [sessions]
  (->> sessions
       (mapv session-minutes)))

(defn collides?
  "Works on both sessions and session-templates.
  Merges in `:tick/beginning` and `:tick/end` to make it a valid interval."
  [a b]
  (if (and (some? a)
           (some? b))
    (not (some? (some #{:precedes :preceded-by :meets :met-by} ;; checks that these are not the relation
                      [(t/relation (make-session-ish-interval a)
                                   (make-session-ish-interval b))])))
    false))

(defn combinations
  "Yanked from https://stackoverflow.com/a/6450455"
  [n coll]
  (if (= 1 n)
    (map list coll)
    (lazy-seq
      (when-let [[head & tail] (seq coll)]
        (concat (for [x (combinations (dec n) tail)]
                  (cons head x))
                (combinations n tail))))))

(defn tmp-session
  [start stop tags]
  {:session/start start
   :session/stop  stop
   :session/tags  tags
   :session/id    (random-uuid)
   :session/tmp   true})

(defn session-ish-overlaps-collision-group?
  [session-ish c-group]
  (some? (->> c-group
              (some #(touches session-ish %)))))

(defn insert-into-collision-group
  [collision-groups session-ish]
  (let [collision-groups-with-trailing-empty
        (if (empty? (last collision-groups))
          collision-groups
          (conj collision-groups []))]

    (setval

      (sp/cond-path
        ;;put the session in the first group that collides
        [(sp/subselect sp/ALL (partial session-ish-overlaps-collision-group? session-ish)) sp/FIRST]
        [(sp/subselect sp/ALL (partial session-ish-overlaps-collision-group? session-ish)) sp/FIRST sp/AFTER-ELEM]

        ;; otherwise put it in the first empty
        [(sp/subselect sp/ALL empty?) sp/FIRST]
        [(sp/subselect sp/ALL empty?) sp/FIRST sp/AFTER-ELEM])

      session-ish
      collision-groups-with-trailing-empty)))

(defn get-collision-groups
  [session-ishes]
  (->> session-ishes
       (reduce insert-into-collision-group [[]])
       (remove empty?)
       vec))

(defn smoosh-sessions
  [sessions]
  (->> sessions
       get-collision-groups
       (mapv (fn [cg]
               (loop [time-stamps    (->> cg
                                          (map #(list (:session/start %) (:session/stop %)))
                                          flatten
                                          distinct
                                          sort
                                          rest
                                          vec)
                      acc            []
                      cur-time-stamp (->> cg (sort-by :session/start) first :session/start)]
                 (if (empty? time-stamps)
                   acc
                   (recur (->> time-stamps rest vec)
                          (conj acc {:session/start cur-time-stamp :session/stop (first time-stamps)})
                          (->> time-stamps first))))))
       flatten
       (mapv (fn [smooshed-session]
               (merge smooshed-session
                      {:session/tags (->> sessions
                                          (filter #(touches % smooshed-session))
                                          (mapv :session/tags)
                                          (apply concat)
                                          distinct
                                          vec)})))))

(def blank-color "#444444")

(defn mix-tag-colors
  "Takes in a vec of color objects and outputs a map with :mixed-color"
  [tag-colors]
  (->> tag-colors
       vec
       (reduce-kv
         ;; reduce-kv and i are remnants of trying to make
         ;; some sort of mixing algorithm dependent on tag position
         (fn [{:keys [mixed-color]} i c2]
           {:mixed-color
            (cond
              (and (is-color? mixed-color)
                   (is-color? c2))
              (-> mixed-color
                  (j/call :mix c2
                          (max 0.5
                               (-> 1 (/ (count tag-colors))))))

              (is-color? mixed-color)
              mixed-color

              (is-color? c2)
              c2)})
         {:mixed-color (or (first tag-colors)
                           ;; TODO is this a good default?
                           ;; should this default live somewhere else?
                           (color blank-color))})))

(defn replace-tag-refs-with-objects
  [indexed-tags session-ish]
  (->> session-ish
       (transform [(sp/cond-path
                     (sp/must :session/tags) :session/tags
                     (sp/must :session-template/tags) :session-template/tags
                     (sp/must :tag-group/tags) :tag-group/tags)]
                  (fn [tag-ids] (->> tag-ids (mapv #(-> indexed-tags (get %))))))))

(defn set-session-ish-color
  "Tag refs must be replaced with values and session-ish/tag colors must be color objects"
  [{:keys [hex]} session-ish]
  (->> session-ish
       (transform []
                  (fn [{session-color          :session/color
                        session-template-color :session-template/color
                        :as                    s}]
                    (let [c               (or session-color session-template-color)
                          tag-colors-path [(sp/cond-path
                                             (sp/must :session/tags)          :session/tags
                                             (sp/must :session-template/tags) :session-template/tags)
                                           sp/ALL
                                           (sp/keypath :tag/color)]
                          tag-colors      (->> s (select tag-colors-path) (remove nil?) vec)]
                      (if-let [c c]
                        ;; when there is a color override just hex it
                        ;; hopefully it is ok to just all key types ...
                        (merge s {:session/color          (if hex (hex-if-some c) c)
                                  :session-template/color (if hex (hex-if-some c) c)})
                        ;; when there is NOT an override color mix tag colors
                        (merge s (let [mixed-color (->> tag-colors
                                                        mix-tag-colors
                                                        :mixed-color
                                                        ((fn [c]
                                                           (if hex
                                                             (hex-if-some c)
                                                             c)))) ]
                                   {:session/color          mixed-color
                                    :session-template/color mixed-color}))))))))

(defn average
  [col]
  (if (empty? col)
    0
    (-> (reduce + col) (/ (count col)))  ))

(def days-of-week [t/MONDAY t/TUESDAY t/WEDNESDAY t/THURSDAY t/FRIDAY t/SATURDAY t/SUNDAY])

(def hours-of-day (-> 24 range vec))

(comment
  (time
    (->> [{:session/start (t/+ (t/now) (t/new-duration 1 :minutes))
           :session/stop  (t/+ (t/now) (t/new-duration 2 :minutes))
           :session/tags  [:a]}
          {:session/start (t/+ (t/now) (t/new-duration 1 :minutes))
           :session/stop  (t/+ (t/now) (t/new-duration 3 :minutes))}
          {:session/start (t/+ (t/now) (t/new-duration 1 :minutes))
           :session/stop  (t/+ (t/now) (t/new-duration 5 :minutes))
           :session/tags  [:c]}

          {:session/start (t/+ (t/now) (t/new-duration 6 :minutes))
           :session/stop  (t/+ (t/now) (t/new-duration 7 :minutes))
           :session/tags  [:c :d]}

          {:session/start (t/+ (t/now) (t/new-duration 8 :minutes))
           :session/stop  (t/+ (t/now) (t/new-duration 10 :minutes))
           :session/tags  [:d :e :f :a]}
          {:session/start (t/+ (t/now) (t/new-duration 9 :minutes))
           :session/stop  (t/+ (t/now) (t/new-duration 12 :minutes))
           :session/tags  [:a]}
          {:session/start (t/+ (t/now) (t/new-duration 10 :minutes))
           :session/stop  (t/+ (t/now) (t/new-duration 11 :minutes))
           :session/tags  []}
          ]
         (smoosh-sessions)
         ;; (transform [sp/ALL] :session/tags)
         )
    )
  )
