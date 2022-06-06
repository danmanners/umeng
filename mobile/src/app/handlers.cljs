(ns app.handlers
  (:require
   ["color" :as color]

   [applied-science.js-interop :as j]
   [re-frame.core :refer [reg-event-db
                          ->interceptor
                          reg-event-fx
                          dispatch
                          debug]]
   [com.rpl.specter :as sp :refer [select select-one setval transform selected-any?]]
   [clojure.spec.alpha :as s]
   [tick.alpha.api :as t]
   [potpuri.core :as p]

   [app.db :as db :refer [default-app-db app-db-spec start-before-stop start-before-stop-times]]
   [app.screens.core :refer [screens]]
   [app.misc :refer [make-color-if-some native-event->time native-event->type]]
   [app.subscriptions :refer [calendar sessions tags report-interval pie-chart-tag-groups]]))

(defn check-and-throw
  "Throw an exception if db doesn't have a valid spec."
  [spec db event]
  (when-not (s/valid? spec db)
    (let [explanation (s/explain-str spec db)]
      (throw (str "Spec check failed: " event " " explanation))
      true)))

(defn validate-spec
  [context]
  (let [db     (-> context :effects :db)
        old-db (-> context :coeffects :db)
        event  (-> context :coeffects :event)]

    (if (some? (check-and-throw app-db-spec db event))
      (assoc-in context [:effects :db] old-db)
      ;; put the old db back as the new db when check fails
      ;; otherwise return context unchanged
      context)))

(def spec-validation
  (if goog.DEBUG
    (->interceptor
      :id :spec-validation
      :after validate-spec)
    ->interceptor))

(def id-gen
  (->interceptor :id :id-gen
                 :before #(assoc-in % [:coeffects :new-uuid] (random-uuid))))

(def insert-now
  (->interceptor :id :insert-now
                 :before #(assoc-in % [:coeffects :now] (t/now))))

(def base-interceptors
  [;; (when ^boolean goog.DEBUG debug) ;; use this for some verbose re-frame logging
   spec-validation])

(defn initialize-db
  [_ _]
  default-app-db)
(reg-event-db :initialize-db [base-interceptors] initialize-db)

(defn set-theme
  [db [_ theme]]
  (->> db (setval [:app-db.settings/theme] theme)))
(reg-event-db :set-theme [base-interceptors] set-theme)

(defn set-version
  [db [_ version]]
  (->> db (setval [:app-db/version] version)))
(reg-event-db :set-version [base-interceptors] set-version)

(defn navigate
  [{:keys [db]} [_ screen-name]]
  {:db       db
   :navigate screen-name
   :fx       [[:dispatch [:set-pie-chart-data-state :stale]]
              [:dispatch [:set-pattern-data-state :stale]]
              [:dispatch [:set-bar-chart-data-state :stale]]]})
(reg-event-fx :navigate [base-interceptors] navigate)

(defn set-tag-remove-modal
  [db [_ {:tag-remove-modal/keys [id visible label]
          hex-color              :tag-remove-modal/color}]]
  (->> db
       (setval [:app-db.view.tag-remove-modal/id] id)
       (setval [:app-db.view.tag-remove-modal/visible] visible)
       (setval [:app-db.view.tag-remove-modal/label] label)
       (setval [:app-db.view.tag-remove-modal/color] (color hex-color))))
(reg-event-db :set-tag-remove-modal [base-interceptors] set-tag-remove-modal)

(defn set-tag-add-modal
  [db [_ {:tag-add-modal/keys [visible]}]]
  (->> db (setval [:app-db.view.tag-add-modal/visible] visible)))
(reg-event-db :set-tag-add-modal [base-interceptors] set-tag-add-modal)

(defn set-date-time-picker
  [db [_ {:date-time-picker/keys [visible
                                  value
                                  mode
                                  session-id
                                  session-template-id
                                  id
                                  field-key]}]]
  (->> db
       (setval [:app-db.view.date-time-picker/field-key] field-key)
       (setval [:app-db.view.date-time-picker/session-id] session-id)
       (setval [:app-db.view.date-time-picker/session-template-id] session-template-id)
       (setval [:app-db.view.date-time-picker/mode] mode)
       (setval [:app-db.view.date-time-picker/value]
               (cond
                 (t/date? value)    (-> value (t/at (t/time "00:00")) t/inst)
                 (t/instant? value) (-> value t/inst)
                 :else              value))
       (setval [:app-db.view.date-time-picker/visible] visible)
       (setval [:app-db.view.date-time-picker/id] id)))
(reg-event-db :set-date-time-picker [base-interceptors] set-date-time-picker)

(defn set-color-picker
  [db [_ {:color-picker/keys [visible value]}]]
  (->> db
       (setval [:app-db.view.color-picker/value]
               (if (some? value) (color value) nil))
       (setval [:app-db.view.color-picker/visible] visible)))
(reg-event-db :set-color-picker [base-interceptors] set-color-picker)

(defn set-selected-day
  [db [_ new-date-inst]]
  (->> db
       (setval [:app-db.selected/day] (-> new-date-inst t/date))))
(reg-event-db :set-selected-day [base-interceptors] set-selected-day)

(defn set-selected-session
  [db [_ session-id]]
  (->> db
       (setval [:app-db.selected/session] session-id)))
(reg-event-db :set-selected-session [base-interceptors] set-selected-session)

(defn set-selected-tag
  [db [_ tag-id]]
  (->> db
       (setval [:app-db.selected/tag] tag-id)))
(reg-event-db :set-selected-tag [base-interceptors] set-selected-tag)

(defn get-dates [start stop]
  (->> (t/range start stop (t/new-duration 1 :minutes))
       (map t/date)
       set
       vec))

(defn re-index-session
  [db [_ {:keys [old-indexes new-indexes id]}]]
  (let [remove-session (fn [sessions]
                         (->> sessions
                              (remove #(= % id))
                              vec))]
    (->> db
         ;; add days that don't exist yet to calendar
         (transform [:app-db/calendar]
                    #(merge
                       (->> new-indexes
                            (map (fn [d] [d {:calendar/date     d
                                             :calendar/sessions []}]))
                            flatten
                            (apply hash-map))
                       %))
         ;; remove from old indexes
         (transform [:app-db/calendar
                     (sp/submap old-indexes)
                     sp/MAP-VALS
                     (sp/keypath :calendar/sessions)]

                    remove-session)

         ;; remove from new indexes (in case they collide with old)
         (transform [:app-db/calendar
                     (sp/submap new-indexes)
                     sp/MAP-VALS
                     (sp/keypath :calendar/sessions)]

                    remove-session)

         ;; add to new indexes
         (transform [:app-db/calendar
                     (sp/submap new-indexes)
                     sp/MAP-VALS
                     (sp/keypath :calendar/sessions)]

                    #(conj % id)))))
(reg-event-db :re-index-session [base-interceptors] re-index-session)

(defn update-session
  "This is not meant to be used with tags, just label start stop type color.
  `:session/remove-color` can be used to remove the color attribute.
  `:session/hex-color` wins over :session/remove-color for setting color.
  Session will not update (no error thrown yet) when stamps are not valid."
  [{:keys [db]} [_ {:session/keys [id color-hex remove-color] :as session}]]
  (let [c              (make-color-if-some color-hex)
        session        (-> session
                           (dissoc :session/color-hex)
                           (dissoc :session/remove-color)
                           (p/update-if-contains :session/start t/instant)
                           (p/update-if-contains :session/stop t/instant))
        start          (:session/start session)
        stop           (:session/stop session)
        old-session    (->> db
                            (select-one [:app-db/sessions
                                         (sp/keypath id)
                                         (sp/submap [:session/start
                                                     :session/stop])]))
        old-start      (:session/start old-session)
        old-stop       (:session/stop old-session)
        stamps-changed (or (some? start) (some? stop))
        start          (or start old-start)
        stop           (or stop old-stop)
        old-indexes    (when stamps-changed (get-dates old-start old-stop))
        new-indexes    (when stamps-changed (get-dates start stop))
        valid-stamps   (start-before-stop {:session/start start
                                           :session/stop  stop})]
    (tap> (p/map-of new-indexes start stop :update-session))
    (when valid-stamps
      (merge
        {:db (->> db
                  (transform [:app-db/sessions (sp/keypath id)]
                             #(merge
                                (if remove-color
                                  (dissoc % :session/color)
                                  %)
                                session
                                (when (some? c) {:session/color c}))))}
        (when stamps-changed
          {:dispatch [:re-index-session (p/map-of old-indexes new-indexes id)]})))))
(reg-event-fx :update-session [base-interceptors] update-session)

(defn add-tag-to-session
  [db [_ {session-id :session/id
          tag-id     :tag/id}]]
  (->> db
       (transform [:app-db/sessions (sp/keypath session-id) :session/tags]
                  #(-> % (conj tag-id) vec))))
(reg-event-db :add-tag-to-session [base-interceptors] add-tag-to-session)

(defn remove-tag-from-session
  [db [_ {session-id :session/id
          tag-id     :tag/id}]]
  (->> db
       (transform [:app-db/sessions (sp/keypath session-id) :session/tags]
                  (fn [tags] (->> tags (remove #(= % tag-id)) vec)))))
(reg-event-db :remove-tag-from-session [base-interceptors] remove-tag-from-session)

;; TODO this is totally untested
(defn set-initial-timestamp
  [db [_ {:keys      [set-start set-stop]
          session-id :session/id}]]
  (->> db
       (transform [:app-db/sessions (sp/keypath session-id)]
                  (fn [{:session/keys [start stop]
                        :as           session}]
                    (merge session
                           (when (and set-start
                                      (nil? start))
                             (if (some? stop)
                               {:session/start
                                (-> stop
                                    (t/- (t/new-duration 60 :minutes)))}
                               ;; TODO inject now
                               {:session/start (t/now)
                                :session/stop  (-> (t/now) (t/+ 60 :minutes))}))
                           (when (and set-stop
                                      (nil? stop))
                             (if (some? start)
                               {:session/stop
                                (-> start
                                    (t/+ (t/new-duration 60 :minutes)))}
                               ;; TODO inject now
                               {:session/start (t/now)
                                :session/stop  (-> (t/now) (t/+ 60 :minutes))})))))))
(reg-event-db :set-initial-timestamp [base-interceptors] set-initial-timestamp)

(defn tick-tock
  [{:keys [now db]} _]
  {:db (->> db (setval [:app-db/current-time] now))
   :fx [[:dispatch [:update-tracking]]]})
(reg-event-fx :tick-tock [base-interceptors insert-now] tick-tock)

(defn create-session-from-event
  [{:keys [db new-uuid now]} [_ event]]
  (let [timezone     (:app-db/current-timezone db)
        zoom         (:app-db.view/zoom db)
        width        (:app-db.view/screen-width db)
        selected-day (:app-db.selected/day db)
        start-time   (-> (p/map-of event zoom) native-event->time)
        start        (-> start-time (t/on selected-day) (t/in timezone) t/instant)
        stop         (-> start (t/+ (t/new-duration 45 :minutes))) ;; TODO make this a setting default
        type         (-> (p/map-of event width) native-event->type)
        session      {:session/id          new-uuid
                      :session/created     now
                      :session/last-edited now
                      :session/start       start
                      :session/stop        stop
                      :session/type        type
                      :session/tags        []}]
    {:db (->> db (setval [:app-db/sessions (sp/keypath new-uuid)] session))
     :fx [[:dispatch [:re-index-session {:old-indexes []
                                         :new-indexes [selected-day]
                                         :id          new-uuid}]]
          [:dispatch [:set-selected-session new-uuid]]
          [:dispatch [:navigate (:session screens)]]]}))
(reg-event-fx :create-session-from-event [base-interceptors id-gen insert-now] create-session-from-event)

(defn set-width-from-event
  [db [_ event]]
  (let [width (-> event (j/get :layout) (j/get :width))]
    (->> db (setval [:app-db.view/screen-width] width))))
(reg-event-db :set-width-from-event [base-interceptors] set-width-from-event)

(defn delete-session
  [{:keys [db]} [_ {:session/keys [id]}]]
  (let [{start :session/start
         stop  :session/stop} (->> db
                                   (select-one [:app-db/sessions
                                                (sp/keypath id)
                                                (sp/submap [:session/start
                                                            :session/stop])]))
        old-indexes           (get-dates start stop)
        new-indexes           []]

    {:db (->> db (transform [:app-db/sessions] #(dissoc % id)))
     :fx [[:dispatch [:re-index-session (p/map-of old-indexes new-indexes id)]]
          [:dispatch [:navigate (:day screens)]]]}))
(reg-event-fx :delete-session [base-interceptors] delete-session)

(defn update-tag
  [db [_ {:tag/keys [id color-hex remove-color] :as tag}]]
  (let [c   (make-color-if-some color-hex)
        tag (-> tag (dissoc :tag/color-hex))]
    (->> db (transform [:app-db/tags (sp/keypath id)]
                       #(merge
                          (if remove-color
                            (dissoc % :tag/color)
                            %)
                          tag
                          (when (some? c) {:tag/color c}))))))
(reg-event-db :update-tag [base-interceptors] update-tag)

(defn delete-tag
  [{:keys [db]} [_ {:tag/keys [id]}]]
  (let [sessions   (->> db (select [:app-db/sesssions
                                    sp/MAP-VALS
                                    :session/tags
                                    #(some? (some #{id} %))]))
        dispatches (->> sessions
                        (map (fn [{session-id :session/id}]
                               [:dispatch [:remove-tag-from-session
                                           {:session/id session-id
                                            :tag/id     id}]])))]

    {:db (->> db (transform [:app-db/tags] #(dissoc % id)))
     :fx (-> [[:dispatch [:navigate (:tags screens)]]]
             (concat dispatches)
             vec)}))
(reg-event-fx :delete-tag [base-interceptors] delete-tag)

(defn create-tag
  [{:keys [db new-uuid]} _]
  {:db (->> db
            (transform [:app-db/tags]
                       #(assoc % new-uuid {:tag/id    new-uuid
                                           :tag/label ""})))
   :fx [[:dispatch [:set-selected-tag new-uuid]]
        [:dispatch [:navigate (:tag screens)]]]})
(reg-event-fx :create-tag [base-interceptors id-gen] create-tag)

(defn start-tracking-session
  [db [_ session-id]]
  (->> db (setval [:app-db/tracking sp/END] [session-id])))
(reg-event-db :start-tracking-session [base-interceptors] start-tracking-session)

(defn stop-tracking-session
  [{:keys [db]} [_ session-id]]
  {:db (->> db (transform [:app-db/tracking] (fn [ids] (->> ids (remove #(= % session-id)) vec))))
   :fx [[:navigate (:day screens)]]})
(reg-event-fx :stop-tracking-session [base-interceptors] stop-tracking-session)

(defn update-tracking
  [{:keys [db now]} [_ _]]
  (let [tracking-ids (->> db (select-one [:app-db/tracking]))]
    {:db db
     :fx (->> db
              (select [:app-db/sessions (sp/submap tracking-ids) sp/MAP-VALS])
              (mapv (fn [{:session/keys [id]}]
                      [:dispatch
                       [:update-session {:session/id   id
                                         :session/stop now}]]))) }))
(reg-event-fx :update-tracking [base-interceptors insert-now] update-tracking)

(defn create-track-session-from-other-session
  [{:keys [db new-uuid now]} [_ from-session-id]]
  (let [today                (t/date now)
        {:session/keys
         [tags color label]} (->> db (select-one [:app-db/sessions
                                                  (sp/keypath from-session-id)
                                                  (sp/submap [:session/tags :session/color :session/label])]))
        session              (-> {:session/id           new-uuid
                                  :session/created      now
                                  :session/last-edited  now
                                  :session/label        label
                                  :session/start        now
                                  :session/stop         (-> now (t/+ (t/new-duration 1 :seconds)))
                                  :session/type         :session/track
                                  :session/tracked-from from-session-id
                                  :session/tags         []}
                                 (merge (when (some? tags) {:session/tags tags}))
                                 (merge (when (some? color) {:session/color color})))]

    (tap> (p/map-of :create-track-session-from-other-session session))

    {:db (->> db (setval [:app-db/sessions (sp/keypath new-uuid)] session))
     :fx [[:dispatch [:re-index-session {:old-indexes []
                                         :new-indexes [today]
                                         :id          new-uuid}]]
          [:dispatch [:navigate (:day screens)]]
          [:dispatch [:start-tracking-session new-uuid]]]}))
(reg-event-fx :create-track-session-from-other-session [base-interceptors id-gen insert-now] create-track-session-from-other-session)

(defn start-ticking
  [{:keys [db]} _]
  {:db            db
   :start-ticking true})
(reg-event-fx :start-ticking [base-interceptors] start-ticking)

(defn stop-ticking
  [{:keys [db]} _]
  {:db           db
   :stop-ticking true})
(reg-event-fx :stop-ticking [base-interceptors] stop-ticking)

(defn check-for-saved-db
  [_ _]
  {:check-for-saved-db true})
;; no spec check "base-interceptors" here
(reg-event-fx :check-for-saved-db check-for-saved-db)

(defn load-db
  [_ [_ new-app-db]]
  {:db new-app-db
   :fx [[:dispatch [:post-load-db]]
        [:dispatch [:start-ticking]]]})
(reg-event-fx :load-db [base-interceptors] load-db)

(defn save-db
  [{:keys [db]} _]
  {:db      db
   :save-db db})
(reg-event-fx :save-db [base-interceptors] save-db)

(defn post-load-db
  [{:keys [db]} _]
  {:db           db
   :post-load-db true})
(reg-event-fx :post-load-db [base-interceptors] post-load-db)

(defn zoom
  [db [_ direction]]
  (->> db
       (transform [:app-db.view/zoom]
                  #(cond-> %
                     (= direction :zoom/in)
                     (* 1.1)
                     (= direction :zoom/out)
                     (* 0.9)))))
(reg-event-db :zoom zoom)

(defn create-track-session-from-nothing
  [{:keys [db new-uuid now]} _]
  (let [today   (t/date now)
        session (-> {:session/id          new-uuid
                     :session/created     now
                     :session/last-edited now
                     :session/label       ""
                     :session/start       now
                     :session/stop        (-> now (t/+ (t/new-duration 1 :seconds)))
                     :session/type        :session/track
                     :session/tags        []})]
    (tap> (p/map-of session :create-track-session-from-nothing))
    {:db (->> db (setval [:app-db/sessions (sp/keypath new-uuid)] session))
     :fx [[:dispatch [:re-index-session {:old-indexes []
                                         :new-indexes [today]
                                         :id          new-uuid}]]
          [:dispatch [:start-tracking-session new-uuid]]]}))
(reg-event-fx :create-track-session-from-nothing [base-interceptors id-gen insert-now] create-track-session-from-nothing)

(defn set-selected-template
  [db [_ template-id]]
  (->> db
       (setval [:app-db.selected/template] template-id)))
(reg-event-db :set-selected-template [base-interceptors] set-selected-template)

(defn set-selected-session-template
  [db [_ session-template-id]]
  (->> db
       (setval [:app-db.selected/session-template] session-template-id)))
(reg-event-db :set-selected-session-template [base-interceptors] set-selected-session-template)

;; TODO remove this because it is unused?
(defn create-template
  [db [_ {id :template/id :as template}]]
  (->> db
       (setval [:app-db/templates (sp/keypath id)] template)))
(reg-event-db :create-template [base-interceptors] create-template)

(defn create-template-from-nothing
  [{:keys [db now new-uuid]} _]
  {:db (->> db
            (setval [:app-db/templates (sp/keypath new-uuid)]
                    {:template/id                new-uuid
                     :template/created           now
                     :template/last-edited       now
                     :template/label             "new template" ;; TODO make a better default
                     :template/session-templates []}))})
(reg-event-fx :create-template-from-nothing [base-interceptors id-gen insert-now] create-template-from-nothing)

;; TODO remove this because it is unused?
(defn create-session-template
  [db [_ {id :session-template/id :as session-template}]]
  (let [selected-template-id (->> db (select-one [:app-db.selected/template]))]
    (->> db
         (setval [:app-db/session-templates (sp/keypath id)] session-template)
         (setval [:app-db/templates (sp/keypath selected-template-id)
                  :template/session-templates sp/AFTER-ELEM] session-template))))
(reg-event-db :create-session-template [base-interceptors] create-session-template)

(defn update-template
  [db [_ {id  :template/id
          :as template}]]
  (->> db (transform [:app-db/templates (sp/keypath id)] #(merge % template))))
(reg-event-db :update-template [base-interceptors] update-template)

(defn update-session-template
  [db [_ {id           :session-template/id
          color-hex    :session-template/color-hex
          remove-color :session-template/remove-color
          :as          session-template}]]
  (tap> (p/map-of :u-s-t session-template))
  (let [c                (make-color-if-some color-hex)
        old-times        (->> db (select-one [:app-db/session-templates
                                              (sp/must id)
                                              (sp/submap [:session-template/start
                                                          :session-template/stop])]))
        session-template (-> session-template
                             (dissoc :session-template/color-hex)
                             (dissoc :session-template/remove-color)
                             (p/update-if-contains :session-template/start #(-> % t/instant t/time))
                             (p/update-if-contains :session-template/stop #(-> % t/instant t/time))
                             (->> (merge old-times)))
        valid-stamps     (start-before-stop-times session-template)]
    (tap> (p/map-of :u-s-t-2 session-template valid-stamps))
    (if valid-stamps
      (->> db
           (transform [:app-db/session-templates (sp/keypath id)]
                      #(merge (if remove-color
                                (dissoc % :session-template/color)
                                %)
                              session-template
                              (when (some? c) {:session-template/color c}))))
      db)))
(reg-event-db :update-session-template [base-interceptors] update-session-template)

(defn delete-template
  [db [_ {id :template/id}]]
  (let [session-template-ids (->> db
                                  (select-one
                                    [:app-db/templates (sp/keypath id)
                                     :template/session-templates]))]
    (->> db
         (transform [:app-db/templates] #(dissoc % id))
         (transform [:app-db/session-templates] #(apply dissoc % session-template-ids)))))
(reg-event-db :delete-template [base-interceptors] delete-template)

(defn delete-session-template
  [{:keys [db]} [_ {id :session-template/id}]]
  (let [selected-template-id (->> db (select-one [:app-db.selected/template]))]
    {:db (->> db
              (transform [:app-db/templates (sp/must selected-template-id)
                          :template/session-templates]
                         (fn [session-template-ids]
                           (->> session-template-ids
                                (remove #(= id %))
                                vec)))
              (transform [:app-db/session-templates] #(dissoc % id)))
     :fx [[:dispatch [:navigate (:template screens)]]
          [:set-selected-session-template nil]]}))

(reg-event-fx :delete-session-template [base-interceptors] delete-session-template)

(defn create-session-template-from-event
  [{:keys [db new-uuid now]} [_ event]]
  (tap> (p/map-of :st-from-event-1 event))
  (let [selected-template-id (->> db (select-one [:app-db.selected/template]))
        zoom                 (:app-db.view/zoom db)
        start                (-> (p/map-of event zoom) native-event->time)
        stop                 (-> start (t/+ (t/new-duration 45 :minutes))) ;; TODO make this a setting default
        session-template     {:session-template/id          new-uuid
                              :session-template/created     now
                              :session-template/last-edited now
                              :session-template/start       start
                              :session-template/stop        stop
                              :session-template/tags        []}]
    {:db (->> db
              (setval [:app-db/session-templates (sp/keypath new-uuid)] session-template)
              (setval [:app-db/templates (sp/keypath selected-template-id)
                       :template/session-templates sp/AFTER-ELEM] new-uuid))
     :fx [[:dispatch [:set-selected-session-template new-uuid]]
          [:dispatch [:navigate (:session-template screens)]]]}))
(reg-event-fx :create-session-template-from-event [base-interceptors id-gen insert-now] create-session-template-from-event)

(defn add-tag-to-session-template
  [db [_ {session-template-id :session-template/id
          tag-id              :tag/id}]]
  (->> db
       (transform [:app-db/session-templates (sp/keypath session-template-id) :session-template/tags]
                  #(-> % (conj tag-id) vec))))
(reg-event-db :add-tag-to-session-template [base-interceptors] add-tag-to-session-template)

(defn remove-tag-from-session-template
  [db [_ {session-template-id :session-template/id
          tag-id              :tag/id}]]
  (->> db
       (transform [:app-db/session-templates (sp/keypath session-template-id) :session-template/tags]
                  (fn [tags] (->> tags (remove #(= % tag-id)) vec)))))
(reg-event-db :remove-tag-from-session-template [base-interceptors] remove-tag-from-session-template)

(defn create-plan-session-from-session-template
  [{:keys [db new-uuid now]} [_ {session-template-id :session-template/id
                                 template-id         :template/id
                                 day                 :day}]]
  (let [day      (or day (t/date now))
        {:session-template/keys
         [tags
          color
          label
          start
          stop]} (->> db (select-one [:app-db/session-templates
                                      (sp/must session-template-id)
                                      (sp/submap [:session-template/tags
                                                  :session-template/color
                                                  :session-template/label
                                                  :session-template/start
                                                  :session-template/stop])]))
        session  (-> {:session/id                              new-uuid
                      :session/created                         now
                      :session/last-edited                     now
                      :session/label                           label
                      :session/start                           (-> start (t/on day) t/instant)
                      :session/stop                            (-> stop (t/on day) t/instant)
                      :session/type                            :session/plan
                      :session/generated-from-template         template-id
                      :session/generated-from-session-template session-template-id
                      :session/tags                            []}
                     (merge (when (some? tags) {:session/tags tags}))
                     (merge (when (some? color) {:session/color color})))]

    (tap> (p/map-of :create-plan-session-from-session-template session))

    {:db (->> db (setval [:app-db/sessions (sp/keypath new-uuid)] session))
     :fx [[:dispatch [:re-index-session {:old-indexes []
                                         :new-indexes [day]
                                         :id          new-uuid}]]]}))
(reg-event-fx :create-plan-session-from-session-template [base-interceptors id-gen insert-now] create-plan-session-from-session-template)

(defn apply-template-to-selected-day
  [{:keys [db]} [_ {template-id :template/id}]]
  (let [selected-day         (->> db (select-one [:app-db.selected/day]))
        session-template-ids (->> db
                                  (select-one [:app-db/templates
                                               (sp/must template-id)
                                               :template/session-templates]))
        session-creates      (->> session-template-ids
                                  (mapv (fn [st-id]
                                          [:dispatch
                                           [:create-plan-session-from-session-template
                                            {:session-template/id st-id
                                             :template/id         template-id
                                             :day                 selected-day}]])))
        all-dispatches       (conj session-creates [:dispatch [:navigate (:day screens)]])]

    (tap> (p/map-of :apply-template-to-selected-day all-dispatches))

    {:db db
     :fx all-dispatches}))
(reg-event-fx :apply-template-to-selected-day [base-interceptors] apply-template-to-selected-day)

(defn create-backup
  [{:keys [db]} _]
  {:db            db
   :create-backup db})
(reg-event-fx :create-backup [base-interceptors] create-backup)

(defn set-backup-keys
  [db [_ ks]]
  (->> db (setval [:app-db/backup-keys] ks)))
(reg-event-db :set-backup-keys [base-interceptors] set-backup-keys)

(defn load-backup-keys
  [{:keys [db]} _]
  {:db               db
   :load-backup-keys true})
(reg-event-fx :load-backup-keys [base-interceptors] load-backup-keys)

(defn delete-backup
  [{:keys [db]} [_ k]]
  {:db            db
   :delete-backup k})
(reg-event-fx :delete-backup [base-interceptors] delete-backup)

(defn restore-backup
  [{:keys [db]} [_ k]]
  {:db             db
   :restore-backup k})
(reg-event-fx :restore-backup [base-interceptors] restore-backup)

(defn export-backup
  [{:keys [db]} [_ k]]
  {:db            db
   :export-backup k})
(reg-event-fx :export-backup [base-interceptors] export-backup)

(defn set-report-interval
  [{:keys [db]} [_ {:keys [beginning end]}]]
  {:db (let [beginning (or beginning (->> db (select-one [:app-db.reports/beginning-date])))
             end       (or end (->> db (select-one [:app-db.reports/end-date])))
             beginning (-> beginning t/date)
             end       (-> end t/date)]
         (if (t/> end beginning)
           (->> db
                (setval [:app-db.reports/beginning-date] beginning)
                (setval [:app-db.reports/end-date] end))
           ;; TODO alert
           db))
   :fx [[:dispatch [:set-pie-chart-data-state :stale]]
        [:dispatch [:set-pattern-data-state :stale]]
        [:dispatch [:set-bar-chart-data-state :stale]]]})
(reg-event-fx :set-report-interval [base-interceptors] set-report-interval)

(defn add-pie-chart-tag-group
  [{:keys [db new-uuid]} _]
  {:db (->> db
            (setval [:app-db.reports.pie-chart/tag-groups (sp/keypath new-uuid)] {:tag-group/id new-uuid}))})
(reg-event-fx :add-pie-chart-tag-group [base-interceptors id-gen] add-pie-chart-tag-group)

(defn add-tag-to-pie-chart-tag-group
  [{:keys [db]} [_ {group-id :pie-chart.tag-group/id
                    tag-id   :tag/id}]]
  {:db (->> db
            (transform [:app-db.reports.pie-chart/tag-groups
                        (sp/must group-id)
                        (sp/keypath :tag-group/tags)] #(conj (or % []) tag-id)))
   :fx [[:dispatch [:set-pie-chart-data-state :stale]]]})
(reg-event-fx :add-tag-to-pie-chart-tag-group [base-interceptors] add-tag-to-pie-chart-tag-group)

(defn set-selected-pie-chart-tag-group
  [db [_ {group-id :pie-chart.tag-group/id}]]

  (->> db (setval [:app-db.reports.pie-chart/selected-tag-group] group-id)))
(reg-event-db :set-selected-pie-chart-tag-group set-selected-pie-chart-tag-group)

(defn remove-tag-from-pie-chart-tag-group
  [{:keys [db]} [_ {group-id :pie-chart.tag-group/id
                    tag-id   :tag/id}]]
  {:db (->> db
            (transform [:app-db.reports.pie-chart/tag-groups
                        (sp/must group-id)
                        (sp/must :tag-group/tags)] (fn [tags]
                                                     (->> tags
                                                          (remove #(= % tag-id))
                                                          vec))))
   :fx [[:dispatch [:set-pie-chart-data-state :stale]]]})
(reg-event-fx :remove-tag-from-pie-chart-tag-group [base-interceptors] remove-tag-from-pie-chart-tag-group)

(defn remove-pie-chart-tag-group
  [{:keys [db]} [_ {group-id :pie-chart.tag-group/id}]]
  {:db (->> db
            (transform [:app-db.reports.pie-chart/tag-groups ] #(dissoc % group-id)))
   :fx [[:dispatch [:set-pie-chart-data-state :stale]]]})
(reg-event-fx :remove-pie-chart-tag-group [base-interceptors] remove-pie-chart-tag-group)

(defn create-backups-directory
  [{:keys [db]} _]
  {:db                       db
   :create-backups-directory true})
(reg-event-fx :create-backups-directory [base-interceptors] create-backups-directory)

(defn set-strictness-for-pie-chart-tag-group
  [{:keys [db]} [_ {group-id   :pie-chart.tag-group/id
                    strictness :tag-group/strict-match}]]
  {:db (->> db
            (setval [:app-db.reports.pie-chart/tag-groups
                     (sp/must group-id)
                     (sp/keypath :tag-group/strict-match)] strictness))
   :fx [[:dispatch [:set-pie-chart-data-state :stale]]]})
(reg-event-fx :set-strictness-for-pie-chart-tag-group [base-interceptors] set-strictness-for-pie-chart-tag-group)

(defn set-pie-chart-data
  [{:keys [db]} [_ new-data]]
  {:db (->> db (setval [:app-db.reports.pie-chart/data] new-data))
   :fx [[:dispatch [:set-pie-chart-data-state :valid]]]})
(reg-event-fx :set-pie-chart-data [base-interceptors] set-pie-chart-data)

(defn set-pie-chart-data-state
  [db [_ new-state]]
  (->> db (setval [:app-db.reports.pie-chart/data-state] new-state)))
(reg-event-db :set-pie-chart-data-state [base-interceptors] set-pie-chart-data-state)

(defn generate-pie-chart-data
  [{:keys [db]} _]
  {:db db
   :fx [[:dispatch [:set-pie-chart-data-state :loading]]
        [:generate-pie-chart-data {:calendar        (calendar db nil)
                                   :sessions        (sessions db nil)
                                   :tags            (tags db nil)
                                   :report-interval (report-interval db nil)
                                   :tag-groups      (pie-chart-tag-groups db nil)}]]})
(reg-event-fx :generate-pie-chart-data [base-interceptors] generate-pie-chart-data)

(defn set-pattern-data
  [{:keys [db]} [_ new-data]]
  {:db (->> db (setval [:app-db.reports.pattern/data] new-data))
   :fx [[:dispatch [:set-pattern-data-state :valid]]]})
(reg-event-fx :set-pattern-data [base-interceptors] set-pattern-data)

(defn set-pattern-data-state
  [db [_ new-state]]
  (->> db (setval [:app-db.reports.pattern/data-state] new-state)))
(reg-event-db :set-pattern-data-state [base-interceptors] set-pattern-data-state)

(defn generate-pattern-data
  [{:keys [db]} _]
  {:db db
   :fx [[:dispatch [:set-pattern-data-state :loading]]
        [:generate-pattern-data {:calendar        (calendar db nil)
                                 :sessions        (sessions db nil)
                                 :tags            (tags db nil)
                                 :report-interval (report-interval db nil)}]]})
(reg-event-fx :generate-pattern-data [base-interceptors] generate-pattern-data)

(defn set-bar-chart-data
  [{:keys [db]} [_ new-data]]
  {:db (->> db (setval [:app-db.reports.bar-chart/data] new-data))
   :fx [[:dispatch [:set-bar-chart-data-state :valid]]]})
(reg-event-fx :set-bar-chart-data [base-interceptors] set-bar-chart-data)

(defn set-bar-chart-data-state
  [db [_ new-state]]
  (->> db (setval [:app-db.reports.bar-chart/data-state] new-state)))
(reg-event-db :set-bar-chart-data-state [base-interceptors] set-bar-chart-data-state)

(defn generate-bar-chart-data
  [{:keys [db]} _]
  {:db db
   :fx [[:dispatch [:set-bar-chart-data-state :loading]]
        [:generate-bar-chart-data {:calendar        (calendar db nil)
                                   :sessions        (sessions db nil)
                                   :tags            (tags db nil)
                                   :report-interval (report-interval db nil)}]]})
(reg-event-fx :generate-bar-chart-data [base-interceptors] generate-bar-chart-data)
