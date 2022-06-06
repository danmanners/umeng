(ns app.screens.reports
  (:require
   ["react-native" :as rn]
   ["react-native-paper" :as paper]
   ["react-native-chart-kit" :as charts]
   ["react-native-modal-datetime-picker" :default DateTimePicker]

   [applied-science.js-interop :as j]
   [reagent.core :as r]

   [app.components.time-related :as tm]
   [app.components.tag-related :as tags]
   [app.screens.core :refer [screens]]
   [app.components.generic-top-section :as top-section]
   [app.misc :refer [<sub >evt get-theme clear-datetime-picker]]
   [app.tailwind :refer [tw]]
   [app.db :refer [generate-color]]))

(defn color-opacity-based
  ([] (color-opacity-based 1))
  ([opacity]
   (str "rgba(255, 255, 255, " opacity ")")))

(def chart-config
  {:backgroundGradientFrom        "#1E2923"
   :backgroundGradientFromOpacity 0,
   :backgroundGradientTo          "#08130D"
   :backgroundGradientToOpacity   0.5
   :color                         color-opacity-based
   :strokeWidth                   2
   :barPercentage                 0.5
   :useShadowColorFromDataset     false})

(defn re-calc-button
  [{:keys [data-state on-press]}]
  [:> rn/View {:style (tw "mt-6 mb-3")}
   (case data-state
     :stale   [:> paper/Button {:icon     "refresh"
                                :mode     "contained"
                                :on-press on-press}
               "re-calculate"]
     :loading [:> paper/Text "Loading..."]
     :valid   [:> rn/View])])

(defn pattern-graph []
  (let [data       (<sub [:pattern-data])
        data-state (<sub [:pattern-data-state])
        cell-style (tw "w-3 h-6")]
    [:> rn/View
     [re-calc-button {:data-state data-state
                      :on-press   #(>evt [:generate-pattern-data])}]
     [:> rn/View (tw "p-2 my-6")
      [:> rn/View (tw "flex flex-col")
       [:> rn/View {:style (tw "flex flex-row justify-between pb-1")}
        ;; time indicators
        [:> rn/View (tw "w-10")]
        (for [t (-> 24 range vec)]
          [:> rn/View {:style cell-style
                       :key   (str (random-uuid))}
           (when (or (= 0 (mod t 6)) (= 23 t))
             [:> paper/Text {:style (tw "text-xs w-4")} t])])]
       ;; data display
       (for [{:keys [day hours]} data]
         [:> rn/View {:style (tw "flex flex-row justify-between pb-1")
                      :key   (str (random-uuid))}
          [:> rn/View (tw "w-10")
           [:> paper/Text  day]]
          (for [c hours]
            [:> rn/View {:style (merge cell-style
                                       {:background-color c})
                         :key   (str (random-uuid))}])])]]]))

(defn pie-chart []
  (let [data                             (<sub [:pie-chart-data])
        data-state                       (<sub [:pie-chart-data-state])
        tag-groups                       (<sub [:pie-chart-tag-groups-hydrated])
        {selected-id :tag-group/id
         :as         selected-tag-group} (<sub [:pie-chart-selected-tag-group])]
    [:> rn/View (tw "px-2 py-6 flex flex-col")
     [re-calc-button {:data-state data-state
                      :on-press   #(>evt [:generate-pie-chart-data])}]
     [:> charts/PieChart
      {:data            (j/lit data)
       :width           400
       :height          250
       :chartConfig     (j/lit chart-config)
       :accessor        "min"
       ;; :hasLegend       false
       ;; :center          (j/lit [90 0])
       :backgroundColor "transparent"
       :paddingLeft     "15"}]

     (for [[id {color      :tag-group/color
                tags       :tag-group/tags
                label      :tag-group-render/label
                strictness :tag-group/strict-match}] tag-groups]
       (if (= id selected-id)
         [:> rn/View {:key id :style (tw "flex pb-6 flex-col items-start")}
          [tags/tags-component {:add-fn    #(>evt [:add-tag-to-pie-chart-tag-group
                                                   {:pie-chart.tag-group/id id
                                                    :tag/id                 %}])
                                :remove-fn #(>evt [:remove-tag-from-pie-chart-tag-group
                                                   {:pie-chart.tag-group/id id
                                                    :tag/id                 %}])
                                :tags      tags}]
          [:> paper/Button {:icon     (if strictness "approximately-equal" "equal")
                            :on-press #(>evt [:set-strictness-for-pie-chart-tag-group
                                              {:pie-chart.tag-group/id id
                                               :tag-group/strict-match (not strictness)}])}
           (if strictness "loose matching" "strict matching")]
          [:> paper/Button {:icon     "cancel"
                            :on-press #(>evt [:set-selected-pie-chart-tag-group
                                              {:pie-chart.tag-group/id nil}])}
           "stop editing tag group"]
          [:> paper/Button {:icon     "delete"
                            :on-press #(>evt [:remove-pie-chart-tag-group
                                              {:pie-chart.tag-group/id id}])}
           "remove tag group"]]
         [:> rn/View {:key id :style (tw "flex pb-6")}

          [:> paper/Button (merge {:color    color
                                   :mode     "contained"
                                   :on-press #(>evt [:set-selected-pie-chart-tag-group
                                                     {:pie-chart.tag-group/id id}])}
                                  (when strictness {:icon "equal"}))
           label]]))
     [:> paper/Button {:icon     "playlist-plus"
                       :on-press #(>evt [:add-pie-chart-tag-group])} "Add tag group"]]))

(defn stacked-bar-chart []
  (let [data       (<sub [:bar-chart-data])
        data-state (<sub [:bar-chart-data-state])]
    [:> rn/View
     [re-calc-button {:data-state data-state
                      :on-press   #(>evt [:generate-bar-chart-data])}]
     [:> rn/View (tw "p-2 my-6")
      [:> charts/StackedBarChart {:data        (j/lit data)
                                  :width       400
                                  :height      220
                                  :chartConfig (j/lit chart-config)}]]]))

(defn interval-buttons []
  (let [{:keys [beginning-value
                beginning-label
                end-label
                end-value]} (<sub [:report-interval])
        {:date-time-picker/keys [value mode visible field-key]
         dtp-id                 :date-time-picker/id}
        (<sub [:date-time-picker])]

    [:> rn/View (tw "flex py-4 px-2 items-center")
     (when (and (some? value)
                (= dtp-id :report))
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
                                   " as " %))
                        (>evt [:set-report-interval
                               {field-key %}])
                        (>evt clear-datetime-picker))}])

     [:> paper/Subheading "Report interval"]
     [:> rn/View (tw "flex flex-row justify-around")
      [tm/date-button {:value     beginning-value
                       :label     beginning-label
                       :dtp-id    :report
                       :field-key :beginning}]
      [tm/date-button {:value     end-value
                       :label     end-label
                       :dtp-id    :report
                       :field-key :end}]]]))

(defn screen [props]
  (r/as-element
    [(fn []
       (let [theme (->> [:theme] <sub get-theme)]
         ;; TODO justin 2021-05-01 add safe area view, heading, and pattern
         ;;
         [:> rn/SafeAreaView {:style (merge (tw "flex flex-1")
                                            {:background-color (-> theme (j/get :colors) (j/get :background))})}
          [:> paper/Surface {:style (-> (tw "flex flex-1")
                                        (merge {:background-color (-> theme (j/get :colors) (j/get :background))}))}
           [:> rn/ScrollView
            [:> rn/View
             [:> rn/StatusBar {:visibility "hidden"}]

             [top-section/component props (:reports screens)]

             [interval-buttons]

             [:> paper/Divider]

             [pie-chart]

             [:> paper/Divider]

             [pattern-graph]

             [:> paper/Divider]

             [stacked-bar-chart]]]]]))]))
