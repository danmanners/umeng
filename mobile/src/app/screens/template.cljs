(ns app.screens.template
  (:require
   ["react-native" :as rn]
   ["react-native-color-picker" :as c]
   ["react-native-gesture-handler" :as g]
   ["react-native-paper" :as paper]
   ["react-native-modal-datetime-picker" :default DateTimePicker]

   [applied-science.js-interop :as j]
   [potpuri.core :as p]
   [reagent.core :as r]
   [tick.alpha.api :as t]

   [app.misc :refer [<sub
                     >evt
                     get-theme
                     active-gesture?]]
   [app.screens.core :refer [screens]]
   [app.components.label :as label]
   [app.components.session-ishes :as session-ishes]
   [app.components.time-indicators :as time-indicators]
   [app.tailwind :refer [tw]]))

(defn apply-button
  [{:keys [id]}]
  [:> paper/Button {:mode     "flat"
                    :icon     "plus-circle-multiple-outline"
                    :style    (tw "mr-4 mt-4 mb-4")
                    :on-press #(>evt [:apply-template-to-selected-day {:template/id id}])}
   "Apply Template"])

(defn screen [props]
  (r/as-element
    [(fn [props]
       (let [theme                     (->> [:theme] <sub get-theme)
             session-templates         (<sub [:session-templates-for-selected-template])
             zoom                      (<sub [:zoom])
             {:template/keys [id
                              label]
              :as            template} (<sub [:selected-template])]

         [:> rn/ScrollView {:style {:background-color
                                    (-> theme (j/get :colors) (j/get :background))}}
          [:> paper/Surface {:style (-> (tw "flex flex-1")
                                        ;; TODO justin 2020-01-23 Move this to tailwind custom theme
                                        ;; (merge {:background-color (-> theme (j/get :colors) (j/get :background))})
                                        )}

           [:> rn/View {:style (tw "flex p-4 flex-col")}

            [apply-button (p/map-of id)]

            [label/component {:label     label
                              :update-fn #(>evt [:update-template {:template/label %
                                                                   :template/id    id}])}]

            [:> g/LongPressGestureHandler
             {:min-duration-ms 800
              :on-handler-state-change
              (fn [e]
                (let [is-active (active-gesture? e)]
                  (when is-active
                    (>evt [:create-session-template-from-event
                           (j/get e :nativeEvent)]))))}
             [:> rn/View
              {:style {:height        (-> 1440 (* zoom))
                       :margin-bottom 256
                       :margin-top    32}}

              [time-indicators/component]

              [session-ishes/component
               {:session-ishes      session-templates
                :long-press-handler (fn [_ _ _])
                :button-handler     (fn [_ id _]
                                      (>evt [:navigate (:session-template screens)])
                                      (>evt [:set-selected-session-template id]))}]

              ]]]]]))]))
