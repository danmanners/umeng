(ns app.components.generic-top-section
  (:require
   ["react-native" :as rn]
   ["react-native-paper" :as paper]

   [applied-science.js-interop :as j]

   [app.components.menu :as menu]
   [app.misc :refer [<sub get-theme]]
   [app.tailwind :refer [tw]]))

(defn component [props screen-heading]
  (let [theme         (->> [:theme] <sub get-theme)
        menu-color    (-> theme
                          (j/get :colors)
                          (j/get :text))
        toggle-drawer (-> props
                          (j/get :navigation)
                          (j/get :toggleDrawer))]

    [:> rn/View {:style (tw "flex flex-row items-center pb-2 pt-2")}
     [menu/button {:button-color menu-color
                   :toggle-menu  toggle-drawer}]
     [:> paper/Title {:style (tw "ml-4")} screen-heading]]))
