(ns app.db
  (:require
   ["color" :as color]
   ["faker" :as faker]
   ["node-emoji" :as emoji]
   ["expo-localization" :as localization]

   [applied-science.js-interop :as j]
   [com.rpl.specter :as sp :refer [select select-one setval transform selected-any?]]
   [clojure.spec.alpha :as s]
   [spec-tools.data-spec :as ds]
   [spec-tools.core :as st]
   [tick.alpha.api :as t]
   [tick.timezone]
   [cljs.reader :refer [read-string]] ;; TODO justin 2021-09-26 is this a security threat?
   [clojure.spec.gen.alpha :as gen]
   [clojure.string :refer [replace]]
   ;; needed to `gen/sample` or `gen/generate`
   [clojure.test.check.generators]

   [app.colors :refer [material-500-hexes]]
   [app.misc :refer [touches chance is-color? hex-if-some make-color-if-some]    ]))

;;
;; misc
;;

(def reasonable-date-times
  (memoize
    ;; generating this range repeatedly is costly so it's _cached_ with memoize
    ;; not sure if this is actually useful or not
    (fn []
      (t/range (-> (t/yesterday) (t/bounds) (t/beginning))
               (-> (t/tomorrow) (t/bounds) (t/end))
               (t/new-duration 1 :minutes)))))

(def arbitrary-date-times
  (memoize
    ;; generating this range repeatedly is costly so it's _cached_ with memoize
    ;; not sure if this is actually useful or not
    (fn [d]
      (t/range (-> d (t/bounds) (t/beginning))
               (-> d (t/bounds) (t/end))
               (t/new-duration 1 :minutes)))))

(defn start-before-stop [{:session/keys [start stop]}]
  (and (t/instant? start)
       (t/instant? stop)
       (t/< start stop)))

(defn start-before-stop-times [{:session-template/keys [start stop]}]
  (and (t/time? start)
       (t/time? stop)
       (t/< start stop)))
;;
;; independent generators
;;

(defn generate-instant []
  (->> (reasonable-date-times)
       (rand-nth)
       (t/instant)))

(defn generate-time []
  (->> (reasonable-date-times)
       (rand-nth)
       (t/time)))

(defn generate-duration []
  (-> (rand-int 99)
      (+ 1)
      (t/new-duration :minutes)))

(defn generate-color []
  ;; (-> faker (j/get :internet) (j/call :color) color)
  (-> material-500-hexes rand-nth color))

(defn generate-session
  "By default will make a session that is contained within a day.
  The `:day` option allows you to choose the day.
  When `:within` option is set to _false_ then there is a chance for `:session/start` and/or `:session/stop` to be on the prev or next day respectively."
  ([] (generate-session {:day    (t/date (generate-instant))
                         :within true}))
  ([{:keys [day within] :or {day    (t/date (generate-instant))
                             within true}}]
   (let [instant        (-> day arbitrary-date-times rand-nth t/instant)
         random-minutes (-> (t/new-duration 4 :hours)
                            (t/minutes)
                            (rand-int)
                            (t/new-duration :minutes))
         start          (if within
                          instant
                          (t/- instant random-minutes))
         stop           (if within
                          (-> instant
                              (t/+ random-minutes)
                              (t/min (-> day (t/bounds) (t/end) (t/instant))))
                          (-> instant
                              (t/+ random-minutes)))]
     (merge
       #:session {:id          (random-uuid)
                  :start       start
                  :stop        stop
                  :created     start
                  :last-edited stop
                  :type        (if (chance :med)
                                 :session/plan :session/track)}
       (when (chance :med)
         #:session {:label (-> faker (j/get :random) (j/call :words))})
       (when (chance :low)
         #:session {:color (-> faker (j/get :internet) (j/call :color) color)})))))

(comment (-> {:day    (t/date (generate-instant))
              :within false}
             generate-session
             (select-keys [:session/start :session/stop])))

(defn generate-tag []
  (merge #:tag {:id (random-uuid)}
         (when (chance :high)
           #:tag {:color (generate-color)})
         (when (chance :high)
           #:tag {:label (str (-> :high chance
                                  (#(if % (-> emoji (j/call :random) (j/get :emoji))
                                        nil)))
                              (-> :med chance
                                  (#(if % (-> faker (j/get :random) (j/call :words))
                                        nil))))})))

(defn generate-calendar-val []
  {:calendar/sessions []
   :calendar/date     (t/date (generate-instant))})

(defn generate-template []
  (merge
    {:template/uuid              (random-uuid)
     :template/session-templates []}
    (when (chance :med)
      {:template/label (-> faker (j/get :random) (j/call :words))})))
;;
;; coll generators
;;

(defn generate-indexed [n index-fn gen-fn]
  (apply merge
         (->> n
              range
              (map #(let [index (index-fn)]
                      {index (gen-fn index)})))))

(defn generate-sessions [n]
  (generate-indexed
    n
    random-uuid
    (fn [id] (-> (generate-session)
                 (merge {:session/id id})))))

(defn generate-tags [n]
  (generate-indexed
    n
    random-uuid
    (fn [id] (-> (generate-tag)
                 (merge {:tag/id id})))))

(defn generate-calendar [n]
  (generate-indexed
    n
    (fn [] (t/date (generate-instant)))
    (fn [d] {:calendar/sessions []
             :calendar/date     (t/date d)})))

(defn generate-templates [n]
  (generate-indexed
    n
    (fn [] (random-uuid))
    (fn [id] (merge
               {:template/uuid              id
                :template/session-templates []}
               (when (chance :med)
                 {:template/label (-> faker (j/get :random) (j/call :words))})))))
;;
;; relational generators
;;

(defn generate-calendar-tag-sessions []
  ;; TODO specmonstah would make this so much cleaner
  (let [tags     (generate-tags 8)
        sessions (generate-sessions 70)

        sessions-with-tags
        (->> sessions
             (transform
               [sp/MAP-VALS]
               (fn [session]
                 (merge session
                        {:session/tags
                         (->> tags
                              (random-sample 0.5)
                              (select [sp/MAP-VALS :tag/id]))}))))

        days
        (->> sessions
             (select [sp/MAP-VALS
                      (sp/transformed
                        []
                        (fn [{:session/keys [start stop]}]
                          [(t/date start) (t/date stop)]))])
             flatten
             set
             seq)

        empty-calendar
        (->> days count range
             (map #(hash-map))
             (interleave days)
             (apply hash-map))

        calendar
        (->> empty-calendar
             (transform
               [sp/ALL]
               (fn [[day obj]]
                 [day (merge
                        obj
                        {:calendar/date day
                         :calendar/sessions
                         (->> sessions-with-tags
                              (select [sp/MAP-VALS
                                       (fn [{:session/keys [start stop]}]
                                         (touches (->> (t/bounds day)
                                                       (transform [sp/MAP-VALS] t/instant))
                                                  {:tick/beginning start
                                                   :tick/end       stop}))
                                       :session/id]))})])))]

    {:app-db/calendar calendar
     :app-db/sessions sessions-with-tags
     :app-db/tags     tags}))

;;
;; specs with gen
;;

(s/def ::reasonable-number (s/int-in 1 20))

(s/def ::instant (s/with-gen t/instant? #(gen/fmap generate-instant (s/gen int?))))

(s/def ::time (s/with-gen t/time? #(gen/fmap generate-time (s/gen int?))))

(s/def ::duration (s/with-gen t/duration? #(gen/fmap generate-duration (s/gen int?))))

(s/def ::color (s/with-gen is-color?
                 #(gen/fmap
                    generate-color
                    (s/gen int?))))

;; sessions

(def session-data-spec
  (ds/spec {:name ::session-ds
            :spec {:session/id                                       uuid?
                   :session/created                                  ::instant
                   :session/last-edited                              ::instant
                   :session/start                                    ::instant
                   :session/stop                                     ::instant
                   :session/type                                     (s/spec #{:session/track :session/plan})
                   :session/tags                                     [uuid?]
                   (ds/opt :session/label)                           string?
                   (ds/opt :session/color)                           ::color
                   (ds/opt :session/tracked-from)                    uuid?
                   (ds/opt :session/generated-from-session-template) uuid?
                   (ds/opt :session/generated-from-template)         uuid?}}))

(s/def ::session (s/with-gen session-data-spec #(gen/fmap generate-session (s/gen int?))))

(s/def ::sessions (s/with-gen
                    (s/and map? (s/every-kv uuid? ::session))
                    #(gen/fmap generate-sessions (s/gen ::reasonable-number))))

;; tags

(def tag-data-spec
  (ds/spec {:name ::tag-ds
            :spec {:tag/id             uuid?
                   ;; TODO justin 2021-09-18 Add created and last-edited
                   (ds/opt :tag/color) (ds/maybe ::color)
                   (ds/opt :tag/label) (ds/maybe string?)}}))

(s/def ::tag (s/with-gen tag-data-spec #(gen/fmap generate-tag (s/gen int?))))

(s/def ::tags (s/with-gen
                (s/and map? (s/every-kv uuid? ::tag))
                #(gen/fmap generate-tags (s/gen ::reasonable-number))))

;; calendars

(def calendar-val-data-spec
  (ds/spec {:name ::calendar-ds
            :spec {:calendar/date     t/date?
                   :calendar/sessions [uuid?]}})) ;; TODO this should maybe be a set

(s/def ::calendar-val (s/with-gen calendar-val-data-spec #(gen/fmap generate-calendar-val (s/gen int?))))

(s/def ::calendar (s/with-gen
                    (s/and map? (s/every-kv t/date? ::calendar-val))
                    #(gen/fmap generate-calendar (s/gen ::reasonable-number))))

;; other

(s/def ::zoom (s/with-gen
                (s/and float? pos?)
                #(gen/fmap (fn [n] (* n 0.1)) (s/gen ::reasonable-number))))

;; 2021-08-15 I think intentions were meant to be todo items
;; Something to be done but not at any specific time
;; Not sure if I want to keep these

;; 2021-10-14 I think these were meant to be put on the session or template as check list items
(def intention-data-spec
  (ds/spec {:name ::intention-ds
            :spec {:intention/id             uuid?
                   :intention/created        ::instant
                   :intention/last-edited    ::instant
                   :intention/date           t/date?
                   :intention/state          (s/spec #{:intention/completed
                                                       :intention/started
                                                       :intention/outstanding
                                                       :intention/canceled})
                   (ds/opt :intention/tags)  [uuid?]
                   (ds/opt :intention/label) string?
                   (ds/opt :intention/color) ::color}}))

;; TODO Justin 2021-06-20 build generators
(s/def ::intention intention-data-spec)

(s/def ::intentions (s/and map? (s/every-kv uuid? ::intention)))

(def session-template-data-spec
  (ds/spec {:name ::session-template-ds
            :spec {:session-template/id             uuid?
                   :session-template/created        ::instant
                   :session-template/last-edited    ::instant
                   :session-template/start          ::time
                   :session-template/stop           ::time
                   :session-template/tags           [uuid?]
                   (ds/opt :session-template/label) string?
                   (ds/opt :session-template/color) ::color}}))

(s/def ::session-template session-template-data-spec)

(s/def ::session-templates (s/and map? (s/every-kv uuid? ::session-template)))

(def template-data-spec
  (ds/spec {:name ::template-ds
            :spec {:template/label             string?
                   :template/id                uuid?
                   :template/created           ::instant
                   :template/last-edited       ::instant
                   :template/session-templates [uuid?]}}))  ;; TODO this should maybe be a set

(s/def ::template (s/with-gen template-data-spec #(gen/fmap generate-template (s/gen int?))))

(s/def ::templates (s/with-gen
                     (s/and map? (s/every-kv uuid? ::template))
                     #(gen/fmap generate-templates (s/gen ::reasonable-number))))

(s/def ::tag-group-tags (s/coll-of uuid? :kind vector? :distinct true))

(def tag-group-data-spec
  (ds/spec {:name ::tag-group-ds
            :spec {:tag-group/id                    uuid?
                   (ds/opt :tag-group/strict-match) boolean?
                   (ds/opt :tag-group/tags)         ::tag-group-tags
                   (ds/opt :tag-group/color)        ::color}}))

(s/def ::tag-group tag-group-data-spec)

(s/def ::tag-groups (s/and map? (s/every-kv uuid? ::tag-group)))

(s/def ::data-state #{:loading :valid :stale})

(s/def ::days-of-week-abbreviated #{"MON" "TUE" "WED" "THU" "FRI" "SAT" "SUN"})

(def app-db-spec
  (ds/spec
    {:name ::app-db
     :spec
     {:app-db/version                                   string?
      :app-db/current-time                              ::instant
      :app-db/current-timezone                          t/zone?
      :app-db/tracking                                  [uuid?]
      :app-db/calendar                                  ::calendar
      :app-db/sessions                                  ::sessions
      :app-db/tags                                      ::tags
      :app-db/templates                                 ::templates
      :app-db/session-templates                         ::session-templates
      :app-db/backup-keys                               [string?]
      :app-db.selected/session                          (ds/maybe uuid?)
      :app-db.selected/template                         (ds/maybe uuid?)
      :app-db.selected/session-template                 (ds/maybe uuid?)
      :app-db.selected/day                              t/date?
      :app-db.selected/tag                              (ds/maybe uuid?)
      :app-db.settings/theme                            (s/spec #{:light :dark})
      :app-db.view/zoom                                 ::zoom
      :app-db.view/screen-width                         float?
      :app-db.view.tag-remove-modal/id                  (ds/maybe uuid?)
      :app-db.view.tag-remove-modal/visible             boolean?
      :app-db.view.tag-remove-modal/label               (ds/maybe string?)
      :app-db.view.tag-remove-modal/color               (ds/maybe ::color)
      :app-db.view.tag-add-modal/visible                boolean?
      :app-db.view.date-time-picker/visible             boolean?
      :app-db.view.date-time-picker/value               (ds/maybe inst?)
      :app-db.view.date-time-picker/mode                (ds/maybe (s/spec #{"date" "time"}))
      :app-db.view.date-time-picker/session-id          (ds/maybe uuid?)
      :app-db.view.date-time-picker/session-template-id (ds/maybe uuid?)
      :app-db.view.date-time-picker/field-key           (ds/maybe keyword?)
      :app-db.view.date-time-picker/id                  (ds/maybe (s/spec #{:day :session :session-template :report}))
      :app-db.view.color-picker/visible                 boolean?
      :app-db.view.color-picker/value                   (ds/maybe ::color)
      :app-db.reports/beginning-date                    t/date?
      :app-db.reports/end-date                          t/date?
      :app-db.reports.pie-chart/tag-groups              ::tag-groups
      :app-db.reports.pie-chart/selected-tag-group      (ds/maybe uuid?)
      :app-db.reports.pie-chart/data                    [{:name  string?
                                                          :min   number?
                                                          :color string?}]
      :app-db.reports.pie-chart/data-state              ::data-state
      :app-db.reports.pattern/data                      [{:day   ::days-of-week-abbreviated
                                                          :hours [string?]}]
      :app-db.reports.pattern/data-state                ::data-state
      :app-db.reports.bar-chart/data                    {(ds/opt :labels)    [::days-of-week-abbreviated]
                                                         (ds/opt :legend)    [string?]
                                                         (ds/opt :data)      [ [ number? ] ]
                                                         (ds/opt :barColors) [string?]}
      :app-db.reports.bar-chart/data-state              ::data-state}}))

(comment
  (s/explain app-db-spec (merge {:settings {:theme :dark}
                                 :version  "version-not-set"}
                                (generate-calendar-tag-sessions))))

(comment (gen/generate (s/gen app-db-spec)))

;;
;; data
;;

(def default-app-db
  (let [
        ;; uncommen below to use generated data
        ;; cal-tag-sessions (generate-calendar-tag-sessions)
        ;; selected-day (->> cal-tag-sessions
        ;;                   :app-db/calendar
        ;;                   keys
        ;;                   rand-nth)
        selected-day (-> (t/now) (t/date))
        ]
    (merge
      ;; blank default
      {:app-db/calendar          {selected-day {:calendar/date     selected-day
                                                :calendar/sessions []}}
       :app-db/tags              {}
       :app-db/sessions          {}
       :app-db/templates         {}
       :app-db/session-templates {}}

      ;; uncomment bellow to use generated data
      ;; cal-tag-sessions

      {
       :app-db/version                                   "version-not-set"
       :app-db/current-time                              (t/now)
       :app-db/current-timezone                          (-> localization (j/get :timezone) (t/zone))
       :app-db/tracking                                  []
       :app-db/backup-keys                               []
       :app-db.selected/session                          nil
       :app-db.selected/template                         nil
       :app-db.selected/session-template                 nil
       :app-db.selected/tag                              nil
       :app-db.selected/day                              selected-day
       :app-db.settings/theme                            :dark
       :app-db.view/zoom                                 1.25
       :app-db.view/screen-width                         1.0 ;; TODO better default?
       :app-db.view.tag-remove-modal/id                  nil
       :app-db.view.tag-remove-modal/visible             false
       :app-db.view.tag-remove-modal/label               nil
       :app-db.view.tag-remove-modal/color               nil
       :app-db.view.tag-add-modal/visible                false
       :app-db.view.date-time-picker/visible             false
       :app-db.view.date-time-picker/value               nil
       :app-db.view.date-time-picker/mode                nil
       :app-db.view.date-time-picker/session-id          nil
       :app-db.view.date-time-picker/session-template-id nil
       :app-db.view.date-time-picker/field-key           nil
       :app-db.view.date-time-picker/id                  nil
       :app-db.view.color-picker/visible                 false
       :app-db.view.color-picker/value                   nil
       :app-db.reports/beginning-date                    (-> (t/now) (t/- (t/new-duration 7 :days)) (t/date))
       :app-db.reports/end-date                          (-> (t/now) (t/date))
       :app-db.reports.pie-chart/tag-groups              {}
       :app-db.reports.pie-chart/selected-tag-group      nil
       :app-db.reports.pie-chart/data                    []
       :app-db.reports.pie-chart/data-state              :stale
       :app-db.reports.pattern/data                      []
       :app-db.reports.pattern/data-state                :stale
       :app-db.reports.bar-chart/data                    {}
       :app-db.reports.bar-chart/data-state              :stale})))

;;
;; serialization
;;

(defn serialize
  [app-db]
  (->> app-db

       (transform [:app-db.view.tag-remove-modal/color] hex-if-some)
       (transform [:app-db.view.color-picker/value ] hex-if-some)
       (transform [:app-db/tags sp/MAP-VALS (sp/must :tag/color)] hex-if-some)
       (transform [:app-db/sessions sp/MAP-VALS (sp/must :session/color)] hex-if-some)
       (transform [:app-db/session-templates sp/MAP-VALS (sp/must :session-template/color)] hex-if-some)
       (setval [:app-db/backup-keys] []) ;; don't backup backup keys
       (setval [:app-db.reports.pie-chart/data-state] :stale) ;; set data state to stale to prompt refresh on loads
       (setval [:app-db.reports.pattern/data-state] :stale) ;; set data state to stale to prompt refresh on loads
       (setval [:app-db.reports.bar-chart/data-state] :stale) ;; set data state to stale to prompt refresh on loads

       str))

(defn de-serialize
  [app-db]
  (->> app-db

       read-string

       (transform [:app-db.view.tag-remove-modal/color] make-color-if-some)
       (transform [:app-db.view.color-picker/value ] make-color-if-some)
       (transform [:app-db/tags sp/MAP-VALS (sp/must :tag/color)] make-color-if-some)
       (transform [:app-db/sessions sp/MAP-VALS (sp/must :session/color)] make-color-if-some)
       (transform [:app-db/session-templates sp/MAP-VALS (sp/must :session-template/color)] make-color-if-some)
       ))
