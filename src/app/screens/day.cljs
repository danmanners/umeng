(ns app.screens.day
  (:require
   ["color" :as color]
   ["react-native" :as rn]
   ["react-native-gesture-handler" :as g]
   ["react-native-paper" :as paper]
   [applied-science.js-interop :as j]
   [reagent.core :as r]
   [potpuri.core :as p]
   [app.helpers :refer [<sub >evt]]
   [app.components.menu :as menu]))

(def styles
  {:surface           {:flex 1 :justify-content "flex-start"}
   :date-indicator    {:container {:display         "flex"
                                   :flex-grow       1
                                   :flex-direction  "row"
                                   :justify-content "center"}
                       :text      {:font-weight "bold"
                                   :text-align  "center"
                                   :margin      8}}
   :tracking-sessions {:container {}
                       :surface   {:padding 8}}
   :top-section       {:outer {:max-height     128
                               :display        "flex"
                               :flex-direction "column"}
                       :inner {:display         "flex"
                               :flex-direction  "row"
                               :align-items     "center"
                               :justify-content "flex-start"}}} )

(defn date-indicator [{:keys [day-of-week
                              day-of-month
                              year
                              month
                              display-year
                              display-month]}]
  [:> rn/View {:style (-> styles :date-indicator :container)}

   (when display-year
     [:> paper/Text {:style (-> styles :date-indicator :text)} year])
   (when display-month
     [:> paper/Text {:style (-> styles :date-indicator :text)} month])
   [:> paper/Text {:style (-> styles :date-indicator :text)} day-of-week]
   [:> paper/Text {:style (-> styles :date-indicator :text)} day-of-month]])

(defn tracking-sessions []
  (let [theme (<sub [:theme-js])]
    [:> g/ScrollView {:content-container-style
                      (-> styles :tracking-sessions :container)}

     [:> paper/Surface {:style (-> styles :tracking-sessions :surface)}

      (for [r [10 99 55 42]]
        [:> g/RectButton {:key (random-uuid)}
         [:> rn/View {:style {:width  "100%"
                              :height 32
                              :margin 4}}
          [:> rn/View {:style {:position         "absolute"
                               :top              0
                               :left             0
                               :width            (str r "%")
                               :height           32
                               :border-radius    (-> theme (j/get :roundness))
                               :background-color (-> theme (j/get :colors) (j/get :accent))}}]
          [:> rn/View {:style {:position         "absolute"
                               :top              0
                               :left             "50%"
                               :width            8
                               :height           32
                               :background-color (-> theme (j/get :colors) (j/get :accent)
                                                     color
                                                     (j/call :darken 0.32)
                                                     (j/call :hex))}}]]])]]))

(defn top-section [{:keys [menu-color toggle-drawer this-day]}]
  [:> rn/View {:style (-> styles :top-section :outer)}

   [:> rn/View {:style (-> styles :top-section :inner)}
    [menu/button {:button-color menu-color
                  :toggle-menu  toggle-drawer}]

    [date-indicator this-day]]

   [tracking-sessions]])

(defn screen [props]
  (r/as-element
    (let [theme         (<sub [:theme-js])
          menu-color    (-> theme
                            (j/get :colors)
                            (j/get :text))
          toggle-drawer (-> props
                            (j/get :navigation)
                            (j/get :toggleDrawer))
          sessions      (<sub [:sessions-for-this-day])
          this-day      (<sub [:this-day])]

      [:> paper/Surface {:style (-> styles :surface
                                    (merge {:background-color (-> theme (j/get :colors) (j/get :background))}))}
       [:> rn/View
        [:> rn/StatusBar {:visibility "hidden"}]

        [top-section (p/map-of menu-color toggle-drawer this-day)]

        [:> g/ScrollView
         [:> paper/Title (str (count sessions))]]]])))
