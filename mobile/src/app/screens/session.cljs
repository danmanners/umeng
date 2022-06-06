(ns app.screens.session
  (:require
   ["react-native" :as rn]
   ["react-native-paper" :as paper]
   ["react-native-modal-datetime-picker" :default DateTimePicker]

   [applied-science.js-interop :as j]
   [potpuri.core :as p]
   [reagent.core :as r]
   [tick.alpha.api :as t]

   [app.components.color-override :as color-override]
   [app.components.tag-related :as tags]
   [app.components.time-related :as tm]
   [app.components.label :as label]
   [app.components.delete-button :as delete-button]
   [app.misc :refer [<sub >evt get-theme clear-datetime-picker >evt-sync]]
   [app.tailwind :refer [tw]]))

(defn start-button
  [{:keys [id]}]
  [:> paper/Button {:mode     "flat"
                    :icon     "play"
                    :style    (tw "mr-4 mt-4 mb-4")
                    :on-press #(>evt [:create-track-session-from-other-session id])}
   "Start tracking"])

(defn stop-button
  [{:keys [id]}]
  [:> paper/Button {:mode     "contained"
                    :icon     "stop"
                    :style    (tw "mr-4 mt-4 mb-4")
                    :on-press #(>evt [:stop-tracking-session id])}
   "Stop tracking"])

(defn time-stamps-component []
  (let [{:session/keys [id
                        start-date-label
                        start-time-label
                        start-value
                        start-set
                        stop-date-label
                        stop-time-label
                        stop-value
                        stop-set]}
        (<sub [:selected-session])

        {:date-time-picker/keys [value mode visible field-key]
         dtp-id                 :date-time-picker/id
         picker-session-id      :date-time-picker/session-id}
        (<sub [:date-time-picker])]

    [:> rn/View {:style (tw "flex flex-col mb-8")}

     (when (and (some? value)
                (= dtp-id :session))
       [:> DateTimePicker
        {:is-visible           visible
         :is-dark-mode-enabled true
         :date                 value
         :mode                 mode

         :on-hide    #(do
                        (tap> "hidden")
                        (>evt clear-datetime-picker))
         :on-cancel  #(do
                        (tap> "cancelled")
                        (>evt clear-datetime-picker))
         :on-confirm #(do
                        (tap> (str "Update " field-key
                                   " for " picker-session-id
                                   " as " (-> % t/instant)))
                        (>evt [:update-session
                               {field-key   %
                                :session/id picker-session-id}])
                        (>evt clear-datetime-picker))}])

     (if start-set
       [:> rn/View {:style (tw "flex flex-row")}
        [tm/date-button {:value              start-value
                         :id                 id
                         :dtp-id             :session
                         :session-ish-id-key :date-time-picker/session-id
                         :label              start-date-label
                         :field-key          :session/start}]
        [tm/time-button {:value              start-value
                         :id                 id
                         :dtp-id             :session
                         :session-ish-id-key :date-time-picker/session-id
                         :label              start-time-label
                         :field-key          :session/start}]]

       [:> rn/View {:style (tw "flex flex-row")}
        [tm/no-stamp-button {:set-start true
                             :id        id}]])

     (if stop-set
       [:> rn/View {:style (tw "flex flex-row")}
        [tm/date-button {:value              stop-value
                         :id                 id
                         :dtp-id             :session
                         :session-ish-id-key :date-time-picker/session-id
                         :label              stop-date-label
                         :field-key          :session/stop}]
        [tm/time-button {:value              stop-value
                         :id                 id
                         :dtp-id             :session
                         :session-ish-id-key :date-time-picker/session-id
                         :label              stop-time-label
                         :field-key          :session/stop}]]

       [:> rn/View {:style (tw "flex flex-row")}
        [tm/no-stamp-button {:set-stop true
                             :id       id}]])]))

(defn session-type-component [{:keys [type id]}]
  [:> rn/View {:style (tw "flex flex-row mb-8")}
   [:> paper/Button (merge {:style    (tw "mr-4 w-24")
                            :icon     "circle-outline"
                            :mode     (case type
                                        :session/plan "contained"
                                        "flat")
                            :on-press #(>evt [:update-session
                                              {:session/type :session/plan
                                               :session/id   id}])}
                           (when (= type :session/plan)
                             {:disabled true}))
    "plan"]
   [:> paper/Button (merge {:style    (tw "w-24")
                            :icon     "circle-slice-8"
                            :mode     (case type
                                        :session/track "contained"
                                        "flat")
                            :on-press #(>evt [:update-session
                                              {:session/type :session/track
                                               :session/id   id}])}
                           (when (= type :session/track)
                             {:disabled true}))
    "track"]])

(defn screen [props]
  (r/as-element
    [(fn [props]
       (let [theme (->> [:theme] <sub get-theme)

             {:session/keys [id
                             start
                             stop
                             type
                             label
                             tags
                             color-override
                             color]
              :as           session} (<sub [:selected-session])

             is-playing (<sub [:is-selected-playing?])]

         [:> rn/ScrollView {:style {:background-color
                                    (-> theme (j/get :colors) (j/get :background))}}
          [:> paper/Surface {:style (-> (tw "flex flex-1")
                                        ;; TODO justin 2020-01-23 Move this to tailwind custom theme
                                        ;; (merge {:background-color (-> theme (j/get :colors) (j/get :background))})
                                        )}

           [:> rn/View {:style (tw "flex p-4 flex-col")}

            (if is-playing
              [stop-button (p/map-of id)]
              [start-button (p/map-of id)])

            [label/component {:label     label
                              :update-fn #(>evt [:update-session {:session/label %
                                                                  :session/id    id}])}]

            [time-stamps-component (p/map-of start stop id)]

            [session-type-component (p/map-of type id)]

            [:> rn/View {:style (tw "mb-8")}
             [tags/tags-component {:add-fn    #(>evt [:add-tag-to-session
                                                      {:session/id id
                                                       :tag/id     %}])
                                   :remove-fn #(>evt [:remove-tag-from-session
                                                      {:session/id id
                                                       :tag/id     %}])
                                   :tags      tags}]]

            [color-override/component {:update-fn      #(>evt [:update-session
                                                               {:session/color-hex %
                                                                :session/id        id}])
                                       :remove-fn      #(>evt [:update-session
                                                               {:session/remove-color true
                                                                :session/id           id}])
                                       :color          color
                                       :color-override color-override}]

            [delete-button/component {:on-press #(>evt [:delete-session session])}]]]]))]))
