(ns app.subscriptions
  (:require
   ["color" :as color]
   ["faker" :as faker] ;; TODO remove when tracking is implemented
   ["node-emoji" :as emoji]
   [applied-science.js-interop :as j]
   [re-frame.core :refer [reg-sub subscribe]]
   [clojure.string :refer [join]]
   [clojure.set :refer [subset?]]
   [com.rpl.specter :as sp :refer [select
                                   setval
                                   transform
                                   select-one
                                   select-one!]]
   [tick.alpha.api :as t]
   [app.colors :refer [material-500-hexes white black]]
   [app.helpers :refer [touches
                        chance
                        prepend-zero
                        drop-keyword-sections
                        hex-if-some
                        is-color?
                        time-label]]
   [potpuri.core :as p]))

(defn version
  [db _]
  (->> db (select-one! [:app-db/version])))
(reg-sub :version version)

(defn theme
  [db _]
  ;; TODO inject paper from sub? to make testing easier?
  (let [theme-type (->> db (select-one! [:app-db.settings/theme]))]
    (case theme-type
      :light :DefaultTheme
      :dark  :DarkTheme
      :DarkTheme)))
(reg-sub :theme theme)

(defn zoom
  [db _]
  (->> db (select-one! [:app-db.view/zoom])))
(reg-sub :zoom zoom)

(defn selected-day
  [db _]
  (->> db (select-one! [:app-db.selected/day])))
(reg-sub :selected-day selected-day)

(defn calendar
  [db _]
  (->> db (select-one! [:app-db/calendar])))
(reg-sub :calendar calendar)

(defn sessions
  [db _]
  (->> db (select-one! [:app-db/sessions])))
(reg-sub :sessions sessions)

(defn tags
  [db _]
  (->> db (select-one! [:app-db/tags])))
(reg-sub :tags tags)

(defn truncate-session
  [day session]
  (let [{:tick/keys [beginning end]} (t/bounds day)
        beginning                    (t/instant beginning)
        end                          (t/instant end)
        {:session/keys [start stop]} session]
    (merge
      session
      {:session/start-truncated (if (-> start (t/< (t/instant beginning)))
                                  beginning
                                  start)
       :session/stop-truncated  (if (-> stop (t/> (t/instant end)))
                                  end
                                  stop)})))

(defn session-ish-overlaps-collision-group?
  [session-ish c-group]
  (tap> (p/map-of :overlaps? session-ish c-group))
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
       ((fn [x] (tap> (p/map-of :cllsn-1 x)) x))
       (reduce insert-into-collision-group [[]])
       ((fn [x] (tap> (p/map-of :cllsn-2 x)) x))
       (remove empty?)
       ((fn [x] (tap> (p/map-of :cllsn-3 x)) x))
       vec))

(defn instant->top-position
  [i zoom]
  (-> i
      t/date
      t/bounds
      t/beginning
      (#(t/duration {:tick/beginning (t/date-time %)
                     :tick/end       (t/date-time i)}))
      t/minutes
      (* zoom)))

(defn time->top-position
  [time zoom]
  (->> time
       (t/new-interval (t/time "00:00"))
       t/duration
       t/minutes
       (* zoom)))

(defn instant-or-time->top-position
  [x zoom]
  (cond (t/time? x)    (time->top-position x zoom)
        (t/instant? x) (instant->top-position x zoom)))

(defn set-session-ish-color
  "Tag refs must be replaced with values and session/tag colors must be color objects"
  [{:keys [hex]} session-ish]
  (->> session-ish
       (transform []
                  (fn [{session-color          :session/color
                        session-template-color :session-template/color
                        :as                    s}]
                    (let [c                (or session-color session-template-color)
                          tag-colors-path  [(sp/cond-path
                                              (sp/must :session/tags) :session/tags
                                              (sp/must :session-template/tags) :session-template/tags)
                                            sp/ALL
                                            (sp/keypath :tag/color)]
                          ;; need to put :not-a-color in tag color list so that reduce runs
                          ;; the reducer fn which takes care of the case there are no tag colors
                          tag-colors       (->> s (select tag-colors-path) (remove nil?) vec)
                          tag-colors-count (count tag-colors)]
                      (if-let [c c]
                        ;; when there is a session color just hex it
                        ;; hopefully it is ok to just add both types ...
                        (merge s {:session/color          (if hex (hex-if-some c) c)
                                  :session-template/color (if hex (hex-if-some c) c)})
                        ;; when there is NOT a session color mix tag colors
                        (merge s (let [mixed-color (->> tag-colors
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
                                                                                (-> 1 (/ tag-colors-count)))))

                                                               (is-color? mixed-color)
                                                               mixed-color

                                                               (is-color? c2)
                                                               c2)})
                                                          {:mixed-color (or (first tag-colors)
                                                                            ;; TODO is this a good default?
                                                                            ;; should this default live somewhere else?
                                                                            (color "#ababab"))})
                                                        :mixed-color
                                                        ((fn [c]
                                                           (if hex
                                                             (hex-if-some c)
                                                             c))))]
                                   {:session/color          mixed-color
                                    :session-template/color mixed-color}))))))))

(defn replace-tag-refs-with-objects
  [indexed-tags session]
  (->> session
       (transform [(sp/cond-path
                     (sp/must :session/tags) :session/tags
                     (sp/must :session-template/tags) :session-template/tags)]
                  (fn [tag-ids] (->> tag-ids (map #(-> indexed-tags (get %))))))))

(defn set-render-props
  [zoom
   tags
   [collision-index
    {type                         :session/type
     session-id                   :session/id
     session-start-truncated      :session/start-truncated
     session-stop-truncated       :session/stop-truncated
     session-label                :session/label
     session-is-selected          :session/is-selected
     session-is-tracking          :session/is-tracking
     session-tag-refs             :session/tags
     session-template-id          :session-template/id
     session-template-start       :session-template/start
     session-template-stop        :session-template/stop
     session-template-label       :session-template/label
     session-template-tag-refs    :session-template/tags
     session-template-is-selected :session-template/is-selected

     :as session-ish}]]

  (let [type                      type
        id                        (or session-id session-template-id)
        start                     (or session-start-truncated session-template-start)
        stop                      (or session-stop-truncated session-template-stop)
        type-offset               (case type
                                    :session/plan  0
                                    :session/track 50
                                    0)
        collision-offset          (-> collision-index (* 4))
        total-offset              (-> type-offset (+ collision-offset))
        left                      (str total-offset "%")
        width                     (-> 45                   ;; starting width percentage
                                      (- collision-offset)
                                      (str "%"))
        elevation                 (-> collision-index (* 2)) ;; pulled from old code idk why it works
        top                       (instant-or-time->top-position start zoom)
        height                    (-> (t/new-interval start stop)
                                      t/duration
                                      t/minutes
                                      (max 1)
                                      (* zoom))
        {session-color
         :session/color
         session-template-color
         :session-template/color} (->> session-ish
                                       (replace-tag-refs-with-objects tags)
                                       (set-session-ish-color {:hex false}))
        session-ish-color         (or session-color session-template-color)
        text-color-hex            (if (is-color? session-ish-color)
                                    (-> session-ish-color (j/call :isLight) (#(if % black white)))
                                    ;; TODO is this a good default?
                                    black)
        tag-labels                (->> (or session-tag-refs session-template-tag-refs)
                                       (map (fn [tag-id]
                                              (-> tags (get-in [tag-id :tag/label]))))
                                       (remove nil?))
        label                     (str (or session-label
                                           session-template-label) "\n" (join "\n" tag-labels))]
    [collision-index
     (merge session-ish {:session-ish-render/elevation        elevation
                         :session-ish-render/left             left
                         :session-ish-render/top              top
                         :session-ish-render/height           height
                         :session-ish-render/width            width
                         :session-ish-render/label            label
                         :session-ish-render/color-hex        (-> session-color hex-if-some)
                         :session-ish-render/ripple-color-hex (-> session-color (j/call :lighten 0.64) (j/call :hex))
                         :session-ish-render/text-color-hex   text-color-hex
                         :session-ish-render/id               id
                         :session-ish-render/is-selected      (or session-is-selected
                                                                  session-template-is-selected)
                         :session-ish-render/is-tracking      session-is-tracking
                         :session-ish-render/start-label      (time-label start)
                         :session-ish-render/stop-label       (time-label stop)})]))

(defn sessions-for-this-day
  [[selected-day calendar sessions zoom tags selected-session-id tracking-ids] _]
  (comment
    [;; collision groups are an intermediate grouping not in sub result
     #:session-ish-render {:left             0         ;; collision group position and type
                           :top              0         ;; start
                           :elevation        1         ;; collision group position
                           :height           10        ;; duration
                           :color-hex        "#ff00ff" ;; tags mix or :session/color
                           :ripple-color-hex "#ff00ff" ;; tags mix or :session/color lightened
                           :label            "label"   ;; session label and tags depending on settings
                           }])
  ;; TODO include session-id for session editing
  (let [this-day (get calendar selected-day)

        sessions-ready-for-render
        (->> this-day
             :calendar/sessions
             (mapv #(get sessions %))
             (mapv #(truncate-session (:calendar/date this-day) %))
             ;; session/is-selected gets renamed to session-ish-render/is-selected
             (mapv #(merge % {:session/is-selected (= (:session/id %) selected-session-id)}))
             (mapv #(merge % {:session/is-tracking (-> tracking-ids set (some [(:session/id %)]) some?)}))
             (sort-by (fn [s] (->> s
                                   :session/start
                                   (t/new-interval (t/epoch))
                                   t/duration
                                   t/millis)))
             (group-by :session/type)
             (transform [sp/MAP-VALS] get-collision-groups)
             (transform [sp/MAP-VALS sp/ALL sp/INDEXED-VALS]
                        ;; set-render-props are the only keys that come out of this subscription
                        (partial set-render-props zoom tags))
             (select [sp/MAP-VALS])
             flatten)]

    ;; if there is a selected session put it on the end of the list
    (if (some? selected-session-id)
      (let [selected-session
            (->> sessions-ready-for-render
                 (some #(when (:session-ish-render/is-selected %) %)))]
        (-> sessions-ready-for-render
            (->> (remove :session-ish-render/is-selected))
            vec
            (conj selected-session)))
      sessions-ready-for-render)))
(reg-sub :sessions-for-this-day

         :<- [:selected-day]
         :<- [:calendar]
         :<- [:sessions]
         :<- [:zoom]
         :<- [:tags]
         :<- [:selected-session-id]
         :<- [:tracking-ids]

         sessions-for-this-day)

(defn now
  []
  (t/now))
(reg-sub :now now)

(defn this-day
  [[selected-day now] _]
  (let [month (t/month selected-day)
        year  (t/year selected-day)]
    {:day-of-week   (->> selected-day
                         t/day-of-week
                         str)
     :day-of-month  (str (t/day-of-month selected-day))
     :year          (str year)
     :month         (->> month str)
     :selected-day  selected-day
     :display-year  (not= year (t/year now))
     :display-month (or (not= year (t/year now))
                        (not= month (t/month now)))
     :behind-now    (-> now (t/date) (t/> selected-day))
     :beyond-now    (-> now (t/date) (t/< selected-day))}))
(reg-sub :this-day

         :<- [:selected-day]
         :<- [:now]

         this-day)

(defn tracking-ids
  [db _]
  (->> db (select [:app-db/tracking sp/ALL])))
(reg-sub :tracking-ids tracking-ids)

(defn tracking
  [[tracking-ids sessions-indexed tags] _]
  (for [session-id tracking-ids]
    (let [{:session/keys [tracked-from
                          start
                          stop
                          label
                          tags]
           session-color :session/color} (->> sessions-indexed
                                              (select-one! [(sp/keypath session-id)])
                                              (replace-tag-refs-with-objects tags)
                                              (set-session-ish-color {:hex false}))
          {tf-start :session/start
           tf-stop  :session/stop}       (->> sessions-indexed (select-one! [(sp/keypath tracked-from)]))
          intended-duration              (if (some? tracked-from)
                                           (-> {:tick/beginning tf-start :tick/end tf-stop}
                                               (t/duration)
                                               (t/millis))
                                           ;; TODO find a better default
                                           (-> (t/new-duration 45 :minutes) (t/millis)))
          duration                       (-> {:tick/beginning start :tick/end stop}
                                             (t/duration)
                                             (t/millis))
          surpassed                      (-> duration (> intended-duration))
          relative-width                 (if surpassed
                                           "100%"
                                           (-> duration (/ intended-duration) (* 100) (str "%")))
          indicator-position             (-> intended-duration (/ duration) (* 100) (str "%"))
          tag-labels                     (->> tags (select [sp/ALL :tag/label]))
          label                          (str label " " (join " " tag-labels))]

      #:tracking-render {:id                   session-id
                         :color-hex            (-> session-color (j/call :hex))
                         :background-color-hex (-> session-color (j/call :darken 0.32) (j/call :hex))
                         :indicator-color-hex  (-> session-color (j/call :lighten 0.32) (j/call :hex))
                         :indicator-position   indicator-position
                         :show-indicator       surpassed
                         :ripple-color-hex     (-> session-color (j/call :lighten 0.64) (j/call :hex))
                         :relative-width       relative-width
                         :label                label
                         :text-color-hex       (-> session-color
                                                   (j/call :isLight)
                                                   (#(if % black white)))})))
(reg-sub :tracking

         :<- [:tracking-ids]
         :<- [:sessions]
         :<- [:tags]

         tracking)

(defn hours
  [[selected-day zoom] _]
  (->> (let [intvl (t/bounds selected-day)]
         (t/range
           (t/beginning intvl)
           (t/end intvl)
           (t/new-duration 1 :hours)))
       (map (fn [h]
              (let [hour     (-> h
                                 t/hour)
                    ;; TODO move zoom offset to sub graph
                    y        (-> hour (* 60) (* zoom))
                    hour-str (prepend-zero hour)]
                {:top y
                 :val hour-str})))))
(reg-sub :hours

         :<- [:selected-day]
         :<- [:zoom]

         hours)

(defn current-time
  [db _]
  (->> db (select-one [:app-db/current-time])))
(reg-sub :current-time current-time)

(defn time-indicator
  [[zoom selected-day current-time] _]
  #:current-time-indicator
  {:position          (instant->top-position current-time zoom)
   :display-indicator (= (t/date current-time) (t/date selected-day))
   :label             (time-label current-time)})
(reg-sub :current-time-indicator

         :<- [:zoom]
         :<- [:selected-day]
         :<- [:current-time]

         time-indicator)

(defn tag-removal-modal
  [db _]
  (->> db
       (select-one [(sp/submap [:app-db.view.tag-remove-modal/id
                                :app-db.view.tag-remove-modal/visible
                                :app-db.view.tag-remove-modal/color
                                :app-db.view.tag-remove-modal/label])])
       (transform [(sp/keypath :app-db.view.tag-remove-modal/color)]
                  hex-if-some)
       ;; remove :app-db.view from keyword because legacy subscription consumer
       (transform [sp/MAP-KEYS] #(drop-keyword-sections 2 %))))
(reg-sub :tag-remove-modal tag-removal-modal)

(defn tag-add-modal
  [db _]
  (->> db
       (select-one [(sp/submap [:app-db.view.tag-add-modal/visible])])
       ;; remove :app-db.view from keyword because legacy subscription consumer
       (transform [sp/MAP-KEYS] #(drop-keyword-sections 2 %))))
(reg-sub :tag-add-modal tag-add-modal)

(defn date-time-picker
  [db _]
  (->> db
       (select-one [(sp/submap [:app-db.view.date-time-picker/value
                                :app-db.view.date-time-picker/visible
                                :app-db.view.date-time-picker/mode
                                :app-db.view.date-time-picker/id
                                :app-db.view.date-time-picker/session-id
                                :app-db.view.date-time-picker/field-key])])
       ;; remove :app-db.view from keyword because legacy subscription consumer
       (transform [sp/MAP-KEYS] #(drop-keyword-sections 2 %))))
(reg-sub :date-time-picker date-time-picker)

(defn color-picker
  [db _]
  (->> db
       (select-one [(sp/submap [:app-db.view.color-picker/visible
                                :app-db.view.color-picker/value])])
       ;; remove :app-db.view from keyword because legacy subscription consumer
       (transform [sp/MAP-KEYS] #(drop-keyword-sections 2 %))
       (transform [:color-picker/value]
                  #(when-some [c %] (-> c (j/call :hex))))))
(reg-sub :color-picker color-picker)

(defn selected-session-id
  [db _]
  (->> db (select-one! [:app-db.selected/session])))
(reg-sub :selected-session-id selected-session-id)

(defn selected-tag-id
  [db _]
  (->> db (select-one! [:app-db.selected/tag])))
(reg-sub :selected-tag-id selected-tag-id)

(defn selected-session
  [[selected-session-id sessions tags] _]
  (->> sessions
       (select-one! [(sp/keypath selected-session-id)])
       (transform [] (fn [{c :session/color :as s}]
                       (merge s {:session/color-override (some? c)})))
       (replace-tag-refs-with-objects tags)
       (set-session-ish-color {:hex true})
       (transform [(sp/keypath :session/tags) sp/ALL (sp/keypath :tag/color)]
                  hex-if-some)
       (transform []
                  (fn [{:session/keys [start stop]
                        :as           session}]
                    (merge session
                           (if (some? start)
                             #:session
                             {:start-date-label (-> start t/date str)
                              :start-time-label (-> start t/time time-label)
                              :start-value      (-> start t/inst)
                              :start-set        true}

                             #:session
                             {:start-set false})
                           (if (some? stop)
                             #:session
                             {:stop-date-label (-> stop t/date str)
                              :stop-time-label (-> stop t/time time-label)
                              :stop-value      (-> stop t/inst)
                              :stop-set        true}
                             #:session
                             {:stop-set false}))))))
(reg-sub :selected-session

         :<- [:selected-session-id]
         :<- [:sessions]
         :<- [:tags]

         selected-session)

(defn selected-tag
  [[selected-tag-id tags] _]
  (->> tags
       (select-one! [(sp/keypath selected-tag-id)])
       (transform [:tag/color] hex-if-some)))
(reg-sub :selected-tag

         :<- [:selected-tag-id]
         :<- [:tags]

         selected-tag)

(defn tag-list
  [indexed-tags _]
  (->> indexed-tags
       (select [sp/MAP-VALS])
       (transform [sp/ALL (sp/keypath :tag/color)] hex-if-some)))
(reg-sub :tag-list

         :<- [:tags]

         tag-list)

(defn tags-not-on-selected-session
  [[all-tags selected-session] _]
  (let [not-these-tags (->> selected-session
                            (select [(sp/keypath :session/tags)
                                     sp/ALL
                                     (sp/keypath :tag/id)])
                            set)]
    (->> all-tags
         (remove #(subset? #{(:tag/id %)} not-these-tags)))))
(reg-sub :tags-not-on-selected-session

         :<- [:tag-list]
         :<- [:selected-session]

         tags-not-on-selected-session)

(defn is-selected-playing?
  [[tracking-ids selected-session-id]]
  (some? (some  #{selected-session-id} tracking-ids)))
(reg-sub :is-selected-playing?

         :<- [:tracking-ids]
         :<- [:selected-session-id]

         is-selected-playing?)

(defn templates
  [db _]
  (->> db (select-one! [:app-db/templates])))
(reg-sub :templates templates)

(defn templates-list
  [[templates session-templates]]
  (->> templates
       (select [sp/MAP-VALS])
       (transform [sp/ALL :template/session-templates sp/ALL]
                  #(get session-templates %))))
(reg-sub :templates-list

         :<- [:templates]
         :<- [:session-templates]

         templates-list)

(defn selected-template-id
  [db _]
  (->> db (select-one! [:app-db.selected/template])))
(reg-sub :selected-template-id selected-template-id)

(defn selected-template
  [[selected-template-id templates] _]
  (->> templates
       (select-one! [(sp/keypath selected-template-id)])))
(reg-sub :selected-template

         :<- [:selected-template-id]
         :<- [:templates]

         selected-template)

(defn session-templates
  [db _]
  (->> db (select-one! [:app-db/session-templates])))
(reg-sub :session-templates session-templates)

(defn selected-session-template-id
  [db _]
  (->> db (select-one! [:app-db.selected/session-template])))
(reg-sub :selected-session-template-id selected-session-template-id)

(defn session-templates-for-selected-template
  [[selected-template session-templates zoom tags selected-session-template-id] _]

  (tap> (p/map-of :stfst-1 selected-template session-templates selected-session-template-id))

  (let [session-templates-ready-for-render
        (->> selected-template
             :template/session-templates
             (mapv #(get session-templates %))
             ;; no need to truncate yet - justin (2021-11-02)
             ;; session-template/is-selected gets renamed to session-ish-render/is-selected deeper in the call chain
             ((fn [x] (tap> (p/map-of :stfst-2 x)) x))
             (mapv #(merge % {:session-template/is-selected
                              (= (:session-template/id %) selected-session-template-id)}))
             ((fn [x] (tap> (p/map-of :stfst-3 x)) x))
             (sort-by (fn [s] (->> s
                                   :session-template/start
                                   (t/new-interval (t/time "00:00"))
                                   t/duration
                                   t/millis)))
             ((fn [x] (tap> (p/map-of :stfst-4 x)) x))
             vec ;; get-collision-groups doesn't seem to like empty lists `'()`
             ((fn [x] (tap> (p/map-of :stfst-4.1 x)) x))
             (get-collision-groups)
             ((fn [x] (tap> (p/map-of :stfst-5 x)) x))
             (transform [sp/ALL sp/INDEXED-VALS]
                        ;; set-render-props are the only keys that come out of this subscription
                        (partial set-render-props zoom tags))
             ((fn [x] (tap> (p/map-of :stfst-6 x)) x))
             flatten
             ((fn [x] (tap> (p/map-of :stfst-7 x)) x))
             vec)]

    ;; if there is a selected session put it on the end of the list
    (if (some? selected-session-template-id)
      (let [selected-session-template
            (->> session-templates-ready-for-render
                 (some #(when (:session-ish-render/is-selected %) %)))]
        (-> session-templates-ready-for-render
            (->> (remove :session-ish-render/is-selected))
            vec
            (conj selected-session-template)))
      session-templates-ready-for-render)))
(reg-sub :session-templates-for-selected-template

         :<- [:selected-template]
         :<- [:session-templates]
         :<- [:zoom]
         :<- [:tags]
         :<- [:selected-session-template-id]

         session-templates-for-selected-template)
