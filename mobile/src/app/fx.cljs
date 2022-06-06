(ns app.fx
  (:require
   ["@react-native-async-storage/async-storage" :as async-storage]
   ["expo-constants" :as expo-constants]
   ["expo-file-system" :as expo-file-system]
   ["expo-sharing" :as expo-sharing]
   ["react-native" :as rn]

   [applied-science.js-interop :as j]
   [com.rpl.specter :as sp :refer [select transform]]
   [cljs.core.async :refer [go <!]]
   [cljs.core.async.interop :refer [<p!]]
   [clojure.set :refer [subset?]]
   [re-frame.core :refer [reg-fx]]
   [potpuri.core :as p]
   [tick.alpha.api :as t]

   [app.misc :refer [>evt
                     average
                     days-of-week
                     hours-of-day
                     sessions->min-col
                     session-minutes
                     smoosh-sessions
                     combine-tag-labels
                     hex-if-some
                     make-color-if-some
                     mix-tag-colors
                     blank-color
                     replace-tag-refs-with-objects
                     set-session-ish-color
                     mix-tag-colors]]
   [app.db :as db :refer [default-app-db serialize de-serialize]]
   [app.screens.core :refer [screens]]))

(def !navigation-ref (clojure.core/atom nil))

(def ticker-ref (clojure.core/atom nil))

(defn navigate [name] ;; no params yet
  ;; TODO implement a check that the navigation component has initialized
  ;; https://reactnavigation.org/docs/navigating-without-navigation-prop#handling-initialization
  ;; The race condition is in my favor if the user has to press a component within the navigation container
  (-> @!navigation-ref
      ;; no params yet for second arg
      (j/call :navigate name (j/lit {}))))

(reg-fx :navigate navigate)

(reg-fx :some-fx-example
        (fn [x]
          (println x)))

(reg-fx :start-ticking
        (fn [_]
          (reset! ticker-ref (js/setInterval #(>evt [:tick-tock]) 5000))))

(reg-fx :stop-ticking
        (fn [_]
          (let [ticker-ref-id @ticker-ref]
            (tap> (str "clearing interval " ticker-ref-id))
            (-> ticker-ref-id (js/clearInterval)))))

(def app-db-key "@app_db") ;; TODO remove

(def dd (-> expo-file-system (j/get :documentDirectory)))

(def app-db-file-path (str dd "app_db.edn"))

(def backups-dir (str dd "backups/"))

(reg-fx :check-for-saved-db
        (fn [_]
          (go
            (try
              (-> expo-file-system (j/call :getInfoAsync app-db-file-path)
                  <p!
                  ((fn [info-result]
                     (if (-> info-result (j/get :exists) (= false))
                       ;; file does NOT exist
                       (do
                         (-> rn/Alert (j/call :alert "No app-db file exists"))
                         (>evt [:load-db default-app-db]))
                       ;; file exists load db
                       (go
                         (try
                           (-> expo-file-system (j/call :readAsStringAsync app-db-file-path)
                               <p!
                               ((fn [app-db-str]
                                  (>evt [:load-db (-> app-db-str de-serialize
                                                      ;; this merge handles accretion to the db spec
                                                      (->> (merge default-app-db )))]))))
                           (catch js/Object e
                             (-> rn/Alert (j/call :alert "Failure on readAsStringAsync" (str e))))))))))
              (catch js/Object e
                (-> rn/Alert (j/call :alert "Failure on getInfoAsync" (str e))))))       ))

(reg-fx :save-db
        (fn [app-db]
          (try
            (-> expo-file-system
                (j/call :writeAsStringAsync app-db-file-path (serialize app-db)))
            (catch js/Object e (tap> (str "error saving db to file " e))))))

(def version (-> expo-constants
                 (j/get :default)
                 (j/get :manifest)
                 (j/get :version)))

(def persist-app-db-task "PERSIST_APP_DB")

(reg-fx :post-load-db
        (fn [_]
          (go
            (try
              (tap> "running post load")
              (>evt [:set-version version])
              (>evt [:set-selected-day (t/now)])
              (>evt [:load-backup-keys])
              (-> rn/AppState
                  (j/call :addEventListener
                          "change"
                          (fn [next-app-state]
                            ;; possible values https://reactnative.dev/docs/appstate#app-states

                             ;; The tick rate is rather slow (5 sec as of 2021-10-01)
                             ;; because faster rates interfere with buttons
                             ;; Because of that we want to tick when app state changes
                             ;; so the user doesn't have to wait 5 seconds after opening
                             (>evt [:tick-tock])

                             ;; Persisting the app-db to the file system happens on putting the app in the background
                             ;; Doing it on "active" results in some lag after the ui loads

                             ;; Persisting used to happen on `:tick-tock` but that was performance inhibiting too

                             ;; This doesn't work on closing the app (only backgrounding it) -- on android
                             ;; I guess so ios works ... uncomfirmed
                             (when (not= next-app-state "active")
                               (>evt [:save-db])))))

              (catch js/Object e
                (tap> {:location :post-load-db
                       :error    e}))))))

(reg-fx :load-backup-keys
        (fn [_]
          (go
            (try
              (-> expo-file-system
                  (j/call :readDirectoryAsync backups-dir)
                  <p!
                  js->clj
                  vec
                  (#(>evt [:set-backup-keys %])))
              (catch js/Object e
                (tap> (str "error getting all backup file names " e))
                (-> rn/Alert (j/call :alert "error getting all backup file names " (str e))))))))

(reg-fx :create-backup
        (fn [{version   :app-db/version
              timestamp :app-db/current-time
              :as       app-db}]
          (go
            (try
              (-> expo-file-system
                  (j/call :writeAsStringAsync
                          (str backups-dir (t/date-time timestamp) "--" version ".edn")
                          (serialize app-db)))
              (>evt [:load-backup-keys])
              (catch js/Object e
                (tap> (str "error creating backup " e))
                (-> rn/Alert (j/call :alert "error creating backup " (str e))))))))

(reg-fx :delete-backup
        (fn [k]
          (go
            (try
              (-> expo-file-system
                  (j/call :deleteAsync (str backups-dir k))
                  <p!
                  (#(>evt [:load-backup-keys])))
              (catch js/Object e
                (tap> (str "error deleting backup " e))
                (-> rn/Alert (j/call :alert "error deleting backup " (str e))))))))

(reg-fx :restore-backup
        (fn [k]
          (go
            (try
              (-> expo-file-system
                  (j/call :readAsStringAsync (str backups-dir k))
                  <p!
                  ((fn [local-store-value]
                     (if (some? local-store-value)
                       (do
                         (>evt [:load-db (-> local-store-value de-serialize
                                             ;; this merge handles accretion to the db spec
                                             (->> (merge default-app-db)))])
                         (>evt [:navigate (:day screens)]))
                       (-> rn/Alert (j/call :alert "Unable to restore backup"))))))
              (catch js/Object e
                (tap> (str "error restoring backup " e))
                (-> rn/Alert (j/call :alert "error restoring backup " (str e))))))))

(reg-fx :export-backup
        (fn [k]
          (go
            (try
              (-> expo-sharing
                  (j/call :shareAsync (str backups-dir k))
                  <p!)
              (catch js/Object e
                (tap> (str "error exporting backup " e))
                (-> rn/Alert (j/call :alert "error exporting backup " (str e))))))))

;; All backup functions require the backups directory to exist or they will throw
(reg-fx :create-backups-directory
        (fn [_]
          (try
            (go
              (-> expo-file-system
                  (j/call :getInfoAsync backups-dir)
                  <p!
                  ((fn [info-result]
                     (when (-> info-result (j/get :exists) (= false))
                       (go
                         (-> expo-file-system
                             (j/call :makeDirectoryAsync backups-dir)
                             <p!)))))))
            (catch js/Object e
              (tap> (str "error creating backup directory " e))
              (-> rn/Alert (j/call :alert "error creating backup directory " (str e)))))))

(defn smooshed-and-tagged-sessions-for-interval
  [{:keys [report-interval calendar sessions tags type truncated]
    :or   {type      :session/track
           truncated true}}]
  (let [{beg-intrvl :app-db.reports/beginning-date
         end-intrvl :app-db.reports/end-date}
        report-interval
        days        (vec (t/range beg-intrvl
                                  (t/+ end-intrvl
                                       (t/new-period 1 :days))))
        session-ids (->> calendar
                         (select [(sp/submap days)
                                  sp/MAP-VALS
                                  :calendar/sessions])
                         flatten
                         set
                         vec)
        beg-instant (-> beg-intrvl (t/at "00:00") t/instant)
        end-instant (-> end-intrvl (t/at "23:59") t/instant)]
    (->> sessions
         (select [(sp/submap session-ids)
                  sp/MAP-VALS])
         (filter #(= type (:session/type %)))
         (smoosh-sessions)
         (mapv (partial replace-tag-refs-with-objects tags))
         (mapv (partial set-session-ish-color {:hex true}))
         ;; TODO combine this with truncate-session
         (mapv (fn [session]
                 (if truncated
                   (cond-> session
                     (-> session :session/start (t/< beg-instant))
                     (merge {:session/start beg-instant})
                     (-> session :session/stop (t/> end-instant))
                     (merge {:session/stop end-instant}))
                   session))))))

(defn total-interval-minutes
  [{beg-intrvl :app-db.reports/beginning-date
    end-intrvl :app-db.reports/end-date}]
  (-> (t/between
        (-> beg-intrvl (t/at (t/time "00:00")) (t/instant))
        (-> end-intrvl (t/at (t/time "23:59")) (t/instant)))
      (t/minutes)))

(defn generate-pie-chart-data
  [{:keys [calendar sessions tags report-interval tag-groups]}]
  (go
    (let [sessions-tagged (smooshed-and-tagged-sessions-for-interval
                            (p/map-of calendar report-interval sessions tags))
          tag-groups      (->> tag-groups
                               (select [sp/MAP-VALS])
                               (transform [sp/ALL] #(replace-tag-refs-with-objects tags %)))
          tg-matched      (->> sessions-tagged
                               (mapv (fn [{session-tags :session/tags :as session-tagged}]
                                       (let [this-session-tags (->> session-tags
                                                                    (mapv :tag/id)
                                                                    set)
                                             tag-group         (->> tag-groups
                                                                    (some
                                                                      #(let [tg-set
                                                                             (->> %
                                                                                  (select [:tag-group/tags
                                                                                           sp/ALL
                                                                                           :tag/id])
                                                                                  set)
                                                                             strict-match (:tag-group/strict-match %)]
                                                                         (when (and (seq tg-set) ;; not empty
                                                                                    (if strict-match
                                                                                      (= tg-set this-session-tags)
                                                                                      (subset? tg-set this-session-tags) )
                                                                                    ) %))))
                                             tag-group-id      (:tag-group/id tag-group)
                                             match             (when (some? tag-group-id)
                                                                 {:tag-group/id        tag-group-id
                                                                  :tag-group/color     (->> tag-group
                                                                                            :tag-group/tags
                                                                                            (mapv :tag/color)
                                                                                            mix-tag-colors
                                                                                            :mixed-color
                                                                                            hex-if-some)
                                                                  :combined-tag-labels (-> tag-group
                                                                                           :tag-group/tags
                                                                                           combine-tag-labels)})]
                                         (merge session-tagged match)))))
          has-tg?         (fn [{tag-group-id :tag-group/id}] (some? tag-group-id))
          total-time      (total-interval-minutes report-interval)
          other-time      (->> tg-matched
                               (remove has-tg?)
                               (sessions->min-col)
                               (reduce +))
          matched-total   (->> tg-matched
                               (filter has-tg?)
                               (sessions->min-col)
                               (reduce +))
          chart-config    {:legendFontColor "#7f7f7f"
                           :legendFontSize  15}
          results         (->> tg-matched
                               (filter has-tg?)
                               (group-by :tag-group/id)
                               vals
                               (mapv (fn [session-group]
                                       (merge chart-config
                                              {:name  (-> session-group first :combined-tag-labels)
                                               :min   (->> session-group
                                                           (sessions->min-col)
                                                           (reduce +))
                                               :color (-> session-group first :tag-group/color)}))))
          final-results   (-> results
                              (conj (merge chart-config
                                           {:name  "Untracked"
                                            :min   (-> total-time (- other-time) (- matched-total))
                                            :color blank-color}))
                              (conj (merge chart-config
                                           {:name  "Other"
                                            :min   other-time
                                            :color "#a0a0a0"})))]
      (>evt [:set-pie-chart-data final-results]))))

(reg-fx :generate-pie-chart-data
        (fn [args]
          ;; timeout is a hack to allow for re-render and displaying the loading component
          (-> #(generate-pie-chart-data args)
              (js/setTimeout 500))))

(comment
  (-> (t/now) (t/date) (t/at "00:00") (t/day-of-week))
  (-> (t/now) (t/> (-> (t/yesterday) (t/at "00:00") (t/instant)))))

(defn session-days-of-week
  [{:session/keys [start stop]}]
  (->> (t/range (t/date start) (-> stop t/date (t/+ (t/new-period 1 :days))))
       (concat [(t/date start)]) ;; t/range is empty if start and stop are on the same day
       (mapv #(t/day-of-week %))
       distinct))

(comment
  (-> {:session/start (-> (t/date "2022-01-27") (t/at "10:00") t/instant)
       :session/stop  (-> (t/date "2022-01-28") (t/at "12:00") t/instant)}
      session-days-of-week
      )
  )

(defn session-matches-day-of-week
  ([day-of-week session]
   (session-matches-day-of-week day-of-week nil (session-days-of-week session)))
  ([day-of-week _ session-days-of-week]
   (->> session-days-of-week (some #{day-of-week}) some?)))

(defn session-matches-day-of-week-and-hour
  [day-of-week hour {:session/keys [start stop]
                     :as           session}]
  (let [days (session-days-of-week session)]
    (and
      ;; The session overlaps this day of the week
      (session-matches-day-of-week day-of-week session days)
      (or
        ;; session is within the day and overlapps the hour
        (and (-> day-of-week (= (t/day-of-week start)))
             (-> day-of-week (= (t/day-of-week stop)))
             (-> hour (>= (t/hour start)))
             (-> hour (<= (t/hour stop))))
        ;; left leaning
        (and (-> day-of-week (= (t/day-of-week stop)))
             (-> hour (<= (t/hour stop)))
             (-> days count (>= 2)))
        ;; right leaning
        (and (-> day-of-week (= (t/day-of-week start)))
             (-> hour (>= (t/hour start)))
             (-> days count (>= 2)))
        ;; the session overlaps the entire day of the week
        (-> days count (>= 3))))))

(defn generate-pattern-data
  [{:keys [calendar sessions tags report-interval]}]
  (let [sessions (smooshed-and-tagged-sessions-for-interval
                   (p/map-of calendar sessions tags report-interval sessions))
        results  (->> days-of-week
                      (mapv (fn [day-of-week]
                              {:day   (-> day-of-week str (subs 0 3))
                               :hours (->> hours-of-day
                                           (mapv (fn [hour]
                                                   (->> sessions
                                                        (filter
                                                         (partial
                                                          session-matches-day-of-week-and-hour
                                                          day-of-week hour))
                                                        (map :session/tags)
                                                        flatten
                                                        (filter #(-> % :tag/color hex-if-some))
                                                        (map #(-> %
                                                                  :tag/color
                                                                  hex-if-some
                                                                  (or "#ffffff")))
                                                        frequencies
                                                        (map identity)
                                                        (sort-by second)
                                                        (partition-by second)
                                                        first
                                                        (map first)
                                                        (map make-color-if-some)
                                                        mix-tag-colors
                                                        :mixed-color
                                                        hex-if-some))))})))]
    (>evt [:set-pattern-data results])))

(comment
  (->> {:calendar {(t/date "2022-01-28")
                   {:calendar/sessions [#uuid "f822da04-f58c-4114-bc26-1079ed2db997"]}
                   (t/date "2022-01-29")
                   {:calendar/sessions [#uuid "a93d723a-6f3b-475c-9311-a513f6cce655"
                                        #uuid "f822da04-f58c-4114-bc26-1079ed2db997"]}
                   (t/date "2022-01-30")
                   {:calendar/sessions [#uuid "b371719d-1284-4ad1-a3d2-d2060d1b4fc5"]}}
        :sessions {#uuid "f822da04-f58c-4114-bc26-1079ed2db997"
                   {:session/id    #uuid "f822da04-f58c-4114-bc26-1079ed2db997"
                    :session/tags  [#uuid "ff07f1c6-2361-4d40-a30c-a6a656c8e488"]
                    :session/type  :session/track
                    :session/color (make-color-if-some "#55aabb")
                    :session/start (-> (t/date "2022-01-27") (t/at "22:00") t/instant)
                    :session/stop  (-> (t/date "2022-01-28") (t/at "06:00") t/instant)}
                   #uuid "a93d723a-6f3b-475c-9311-a513f6cce655"
                   {:session/id    #uuid "a93d723a-6f3b-475c-9311-a513f6cce655"
                    :session/tags  []
                    :session/type  :session/track
                    :session/start (-> (t/date "2022-01-29") (t/at "04:00") t/instant)
                    :session/stop  (-> (t/date "2022-01-29") (t/at "06:00") t/instant)}
                   #uuid "b371719d-1284-4ad1-a3d2-d2060d1b4fc5"
                   {:session/id    #uuid "b371719d-1284-4ad1-a3d2-d2060d1b4fc5"
                    :session/tags  []
                    :session/type  :session/track
                    :session/start (-> (t/date "2022-01-29") (t/at "04:00") t/instant)
                    :session/stop  (-> (t/date "2022-01-30") (t/at "06:00") t/instant)}}
        :tags {#uuid "ff07f1c6-2361-4d40-a30c-a6a656c8e488"
               {:tag/id #uuid "ff07f1c6-2361-4d40-a30c-a6a656c8e488"
                :tag/color (make-color-if-some "#bbaa77")}}
        :report-interval {:app-db.reports/beginning-date (t/date "2022-01-28")
                          :app-db.reports/end-date       (t/date "2022-01-30")}}
       generate-pattern-data
       )
  )

(reg-fx :generate-pattern-data
        (fn [args]
          ;; timeout is a hack to allow for re-render and displaying the loading component
          (-> #(generate-pattern-data args)
              (js/setTimeout 500))))

(defn ratio-score [planned-min tracked-min]
  ;; Going 2x over results in a 0 score
  ;; Having 1/2x tracked results in a 50 score
  ;; Having 0x tracked results in a 0 score
  ;; Having 1.5x tracked results results in a 50 score
  (let [ratio (-> (or tracked-min 0) (/ planned-min))]
    (if (-> ratio (> 1))
      (-> 1
          (- (-> ratio (- 1)))
          (->> (j/call js/Math :max 0))
          (* 100))
      (-> ratio (* 100)))))

(comment
  (ratio-score 1000 10)
  (ratio-score 10 1000)
  (ratio-score 50 50)
  (ratio-score 75 50)
  (ratio-score 50 75)
  )

(defn generate-bar-chart-data
  [{:keys [calendar sessions tags report-interval]}]
  (let [sessions-tracked (smooshed-and-tagged-sessions-for-interval
                           (p/map-of calendar sessions tags report-interval sessions))
        type             :session/plan
        sessions-planned (smooshed-and-tagged-sessions-for-interval
                           (p/map-of calendar sessions tags report-interval sessions type))

        data (->> days-of-week
                  (mapv (fn [day-of-week]
                          (let [sessions-planned     (->> sessions-planned
                                                          (filter (partial session-matches-day-of-week day-of-week)))
                                sessions-tracked     (->> sessions-tracked
                                                          (filter (partial session-matches-day-of-week day-of-week)))
                                total-planned        (->> sessions-planned
                                                          sessions->min-col
                                                          (reduce +))
                                total-tracked        (->> sessions-tracked
                                                          sessions->min-col
                                                          (reduce +))
                                total-interval       (total-interval-minutes report-interval)
                                time-logged-score    (-> total-planned
                                                         (+ total-tracked)
                                                         (/ (-> total-interval (* 2)))
                                                         (* 100)
                                                         (->> (j/call js/Math :round)))
                                tag-path             [sp/ALL (sp/keypath :session/tags) sp/ALL (sp/keypath :tag/id)]
                                total-for-tag-id-fn  (fn [sessions]
                                                       (fn [tag-id]
                                                         {:tag/id  tag-id
                                                          :minutes (->> sessions
                                                                        (filter (fn [session]
                                                                                  (some? (some #{tag-id} (select (rest tag-path) session)))))
                                                                        sessions->min-col
                                                                        (reduce +))}))
                                planned-tags         (->> sessions-planned
                                                          (select tag-path)
                                                          distinct)
                                tracked-tags         (->> sessions-tracked
                                                          (select tag-path)
                                                          distinct)
                                total-p-tags         (->> planned-tags
                                                          (mapv (total-for-tag-id-fn sessions-planned)))
                                total-t-tags-indexed (->> tracked-tags
                                                          (mapv (total-for-tag-id-fn sessions-tracked))
                                                          (group-by :tag/id)
                                                          (transform [sp/MAP-VALS] first))
                                plan-tracked-score   (->> total-p-tags
                                                          (mapv (fn [{tag-id  :tag/id
                                                                      minutes :minutes}]
                                                                  (let [tracked-minutes (-> total-t-tags-indexed
                                                                                            (get tag-id)
                                                                                            (get :minutes))]
                                                                    (ratio-score minutes tracked-minutes))))
                                                          average
                                                          (j/call js/Math :round))
                                alignment-score      (->> sessions-planned
                                                          (mapv (fn [{p-start :session/start
                                                                      p-stop  :session/stop
                                                                      :as     ps}]
                                                                  (let [ps-tag-id-set (->> ps (select (rest tag-path)) set)
                                                                        time-aligned  (->> sessions-tracked
                                                                                           (filter #(= (->> % (select (rest tag-path)) set)
                                                                                                       ps-tag-id-set))
                                                                                           (mapv (fn [{t-start :session/start
                                                                                                       t-stop  :session/stop}]
                                                                                                   (let [concur (t/concur {:tick/beginning p-start
                                                                                                                           :tick/end       p-stop}
                                                                                                                          {:tick/beginning t-start
                                                                                                                           :tick/end       t-stop})]
                                                                                                     (if (some? concur)
                                                                                                       (->> concur
                                                                                                            (#(t/between (:tick/beginning %) (:tick/end %)))
                                                                                                            t/minutes)
                                                                                                       0))))
                                                                                           (reduce +))]
                                                                    (ratio-score (session-minutes ps) time-aligned))))
                                                          average
                                                          (j/call js/Math :round))]

                            [time-logged-score plan-tracked-score alignment-score]))))]
    (>evt [:set-bar-chart-data {:labels    (->> days-of-week (mapv #(-> % str (subs 0 3))))
                                :legend    ["time logged" "plan tracked" "alignment"]
                                :data      data
                                :barColors ["#8d8d8d" "#bdbdbd" "#ab47bc"]}])))

(reg-fx :generate-bar-chart-data
        (fn [args]
          ;; timeout is a hack to allow for re-render and displaying the loading component
          (-> #(generate-bar-chart-data args)
              (js/setTimeout 500))))

;; Some helpful repl stuff
(comment
  ;; Hard reset app-db
  (do
    (>evt [:stop-ticking])
    (>evt [:initialize-db])
    (>evt [:save-db]))

  )

;; Useful for testing data gen
(comment
  (require '[re-frame.db])
  (require '[app.subscriptions :refer [calendar sessions tags report-interval]])
  (let [db              @re-frame.db/app-db
        calendar        (calendar db nil)
        sessions        (sessions db nil)
        tags            (tags db nil)
        report-interval (report-interval db nil)
        ]
    )
  )
