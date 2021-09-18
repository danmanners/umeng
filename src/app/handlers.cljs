(ns app.handlers
  (:require
   ["color" :as color]
   [re-frame.core :refer [reg-event-db
                          ->interceptor
                          reg-event-fx
                          dispatch
                          debug]]
   [com.rpl.specter :as sp :refer [select select-one setval transform selected-any?]]
   [clojure.spec.alpha :as s]
   [app.db :as db :refer [default-app-db app-db-spec start-before-stop]]
   [tick.alpha.api :as t]
   [potpuri.core :as p]
   [app.screens.core :refer [screens]]
   [app.helpers :refer [make-color-if-some native-event->time native-event->type]]
   [applied-science.js-interop :as j]))

(defn check-and-throw
  "Throw an exception if db doesn't have a valid spec."
  [spec db event]
  (when-not (s/valid? spec db)
    (let [explanation (s/explain-str spec db)]
      (throw (str "Spec check failed: " explanation))
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

(def base-interceptors  [;; (when ^boolean goog.DEBUG debug) ;; use this for some verbose re-frame logging
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
   :navigate screen-name})
(reg-event-fx :navigate [base-interceptors] navigate)

(defn set-tag-remove-modal
  [db [_ {:tag-remove-modal/keys [id visible label]
          hex-color              :tag-remove-modal/color}]]
  (->> db
       (setval [:app-db.view.tag-remove-modal/id] id)
       (setval [:app-db.view.tag-remove-modal/visible] visible)
       (setval [:app-db.view.tag-remove-modal/label] label)
       (setval [:app-db.view.tag-remove-modal/color] (color hex-color))))
(reg-event-db :set-tag-remove-modal set-tag-remove-modal)

(defn set-tag-add-modal
  [db [_ {:tag-add-modal/keys [visible]}]]
  (->> db (setval [:app-db.view.tag-add-modal/visible] visible)))
(reg-event-db :set-tag-add-modal set-tag-add-modal)

(defn set-date-time-picker
  [db [_ {:date-time-picker/keys [visible
                                  value
                                  mode
                                  session-id
                                  id
                                  field-key]}]]
  (->> db
       (setval [:app-db.view.date-time-picker/field-key] field-key)
       (setval [:app-db.view.date-time-picker/session-id] session-id)
       (setval [:app-db.view.date-time-picker/mode] mode)
       (setval [:app-db.view.date-time-picker/value]
               (cond
                 (t/date? value)    (-> value (t/at (t/time "00:00")) t/inst)
                 (t/instant? value) (-> value t/inst)
                 :else              value))
       (setval [:app-db.view.date-time-picker/visible] visible)
       (setval [:app-db.view.date-time-picker/id] id)))
(reg-event-db :set-date-time-picker set-date-time-picker)

(defn set-color-picker
  [db [_ {:color-picker/keys [visible value]}]]
  (->> db
       (setval [:app-db.view.color-picker/value]
               (if (some? value) (color value) nil))
       (setval [:app-db.view.color-picker/visible] visible)))
(reg-event-db :set-color-picker set-color-picker)

(defn set-selected-day
  [db [_ new-date-inst]]
  (->> db
       (setval [:app-db.selected/day] (-> new-date-inst t/date))))
(reg-event-db :set-selected-day set-selected-day)

(defn set-selected-session
  [db [_ session-id]]
  (->> db
       (setval [:app-db.selected/session] session-id)))
(reg-event-db :set-selected-session set-selected-session)

(defn get-dates [start stop]
  (->> (t/range start stop (t/new-duration 1 :days))
       (map t/date)
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
(reg-event-db :re-index-session re-index-session)

(defn update-session
  "This is not meant to be used with tags, just label start stop type color.
  `:session/remove-color` can be used to remove the color attribute.
  `:session/hex-color` wins over :session/remove-color for setting color.
  Session will not update (no error thrown yet) when stamps are not valid."
  [{:keys [db]} [_ {:session/keys [id color-hex remove-color] :as session}]]
  (let [c              (make-color-if-some color-hex)
        session        (-> session
                           (dissoc :session/color-hex)
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
(reg-event-fx :update-session update-session)

(defn add-tag-to-session
  [db [_ {session-id :session/id
          tag-id     :tag/id}]]
  (->> db
       (transform [:app-db/sessions (sp/keypath session-id) :session/tags]
                  #(conj % tag-id))))
(reg-event-db :add-tag-to-session add-tag-to-session)

(defn remove-tag-from-session
  [db [_ {session-id :session/id
          tag-id     :tag/id}]]
  (->> db
       (transform [:app-db/sessions (sp/keypath session-id) :session/tags]
                  (fn [tags] (->> tags (remove #(= % tag-id)) vec)))))
(reg-event-db :remove-tag-from-session remove-tag-from-session)

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
(reg-event-db :set-initial-timestamp set-initial-timestamp)

(defn tick-tock
  [{:keys [now db]} _]
  {:db (->> db (setval [:app-db/current-time] now))})
(reg-event-fx :tick-tock [insert-now] tick-tock)

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
                      :session/type        type}]
    {:db (->> db (setval [:app-db/sessions (sp/keypath new-uuid)] session))
     :fx [[:dispatch [:re-index-session {:old-indexes []
                                         :new-indexes [selected-day]
                                         :id          new-uuid}]]
          [:dispatch [:set-selected-session new-uuid]]
          [:dispatch [:navigate (:session screens)]]]}))
(reg-event-fx :create-session-from-event [id-gen insert-now] create-session-from-event)

(defn set-width-from-event
  [db [_ event]]
  (let [width (-> event (j/get :layout) (j/get :width))]
    (->> db (setval [:app-db.view/screen-width] width))))
(reg-event-db :set-width-from-event set-width-from-event)

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
(reg-event-fx :delete-session delete-session)
