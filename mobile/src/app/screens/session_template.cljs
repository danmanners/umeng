(ns app.screens.session-template
  (:require
   ["react-native" :as rn]
   ["react-native-paper" :as paper]
   ["react-native-modal-datetime-picker" :default DateTimePicker]

   [applied-science.js-interop :as j]
   [potpuri.core :as p]
   [reagent.core :as r]

   [app.components.label :as label]
   [app.components.tag-related :as tags]
   [app.components.color-override :as color-override]
   [app.components.time-related :as tm]
   [app.components.delete-button :as delete-button]
   [app.misc :refer [<sub >evt get-theme clear-datetime-picker]]
   [app.tailwind :refer [tw]]))

(defn time-stamps-component []
  (let [{:session-template/keys [id
                                 start-time-label
                                 start-value
                                 start-set
                                 stop-time-label
                                 stop-value
                                 stop-set]}
        (<sub [:selected-session-template])

        {:date-time-picker/keys     [value mode visible field-key]
         dtp-id                     :date-time-picker/id
         picker-session-template-id :date-time-picker/session-template-id}
        (<sub [:date-time-picker])]

    [:> rn/View {:style (tw "flex flex-col mb-8")}

     (when (and (some? value)
                (= dtp-id :session-template))
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
                                   " for " picker-session-template-id
                                   " as " %))
                        (>evt [:update-session-template
                               {field-key            %
                                :session-template/id picker-session-template-id}])
                        (>evt clear-datetime-picker))}])

     (if start-set
       [:> rn/View {:style (tw "flex flex-row")}
        [tm/time-button {:value              start-value
                         :id                 id
                         :dtp-id             :session-template
                         :session-ish-id-key :date-time-picker/session-template-id
                         :label              start-time-label
                         :field-key          :session-template/start}]]

       [:> rn/View {:style (tw "flex flex-row")}
        [tm/no-stamp-button {:set-start true
                             :id        id}]])

     (if stop-set
       [:> rn/View {:style (tw "flex flex-row")}
        [tm/time-button {:value              stop-value
                         :id                 id
                         :dtp-id             :session-template
                         :session-ish-id-key :date-time-picker/session-template-id
                         :label              stop-time-label
                         :field-key          :session-template/stop}]]

       [:> rn/View {:style (tw "flex flex-row")}
        [tm/no-stamp-button {:set-stop true
                             :id       id}]])]))

(defn screen [props]
  (r/as-element
    [(fn [props]
       (let [theme (->> [:theme] <sub get-theme)

             {:session-template/keys
              [id
               start
               stop
               label
               tags
               color-override
               color]
              :as session-template} (<sub [:selected-session-template])]

         [:> rn/ScrollView {:style {:background-color
                                    (-> theme (j/get :colors) (j/get :background))}}
          [:> paper/Surface {:style (-> (tw "flex flex-1")
                                        ;; TODO justin 2020-01-23 Move this to tailwind custom theme
                                        ;; (merge {:background-color (-> theme (j/get :colors) (j/get :background))})
                                        )}

           [:> rn/View {:style (tw "flex p-4 flex-col")}

            [label/component {:label     label
                              :update-fn #(>evt [:update-session-template
                                                 {:session-template/label %
                                                  :session-template/id    id}])}]


            [time-stamps-component (p/map-of start stop id)]

            [:> rn/View {:style (tw "mb-8")}
             [tags/tags-component {:add-fn    #(>evt [:add-tag-to-session-template
                                                      {:session-template/id id
                                                       :tag/id              %}])
                                   :remove-fn #(>evt [:remove-tag-from-session-template
                                                      {:session-template/id id
                                                       :tag/id              %}])
                                   :tags      tags}]]

            [color-override/component {:update-fn      #(>evt [:update-session-template
                                                               {:session-template/color-hex %
                                                                :session-template/id        id}])
                                       :remove-fn      #(>evt [:update-session-template
                                                               {:session-template/remove-color true
                                                                :session-template/id           id}])
                                       :color          color
                                       :color-override color-override}]

            [delete-button/component {:on-press #(>evt [:delete-session-template session-template])}]
            ]]]))]))
