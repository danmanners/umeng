(ns app.screens.tags
  (:require
   ["color" :as color]
   ["react-native" :as rn]
   ["react-native-paper" :as paper]

   [applied-science.js-interop :as j]
   [com.rpl.specter :as sp :refer [setval]]
   [reagent.core :as r]

   [app.colors :refer [material-500-hexes]]
   [app.components.generic-top-section :as top-section]
   [app.components.color-picker :as color-picker]
   [app.components.tag-related :as components]
   [app.misc :refer [<sub >evt get-theme chance]]
   [app.screens.core :refer [screens]]
   [app.tailwind :refer [tw]]
   [potpuri.core :as p]))

(defn screen [props]
  (r/as-element
    [(fn []
       (let [theme (->> [:theme] <sub get-theme)
             tags  (<sub [:tag-list])]

         ;; TODO justin 2021-02-07 Do we need safe area view everywhere?
         [:> rn/ScrollView {:style (merge (tw "flex flex-1")
                                          {:background-color (-> theme (j/get :colors) (j/get :background))})}

          [:> rn/StatusBar {:visibility "hidden"}]

          [top-section/component props (:tags screens)]

          [:> rn/View {:style (tw "flex flex-col")}

           [:> rn/View {:style (tw "flex flex-row flex-wrap items-center mb-8")}
            (for [{color :tag/color
                   label :tag/label
                   id    :tag/id} tags]
              (let [on-press #(do (>evt [:set-selected-tag id])
                                  (>evt [:navigate (:tag screens)]))
                    style    (tw "ml-4 mb-4")]
                [:> rn/View {:key   id
                             :style style}
                 [components/tag-button (p/map-of color label on-press)]]))]

           [:> paper/Button {:mode     "flat"
                             :icon     "plus"
                             :on-press #(>evt [:create-tag])} "Add new tag"]]]))]))
