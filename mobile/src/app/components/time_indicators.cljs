(ns app.components.time-indicators
  (:require
   ["react-native" :as rn]
   ["react-native-paper" :as paper]

   [applied-science.js-interop :as j]

   [app.misc :refer [<sub get-theme]]
   [app.tailwind :refer [tw]]))

(defn component []
  (let [theme (->> [:theme] <sub get-theme)
        hours (<sub [:hours])]
    [:> rn/View
     (for [{:keys [top val]} hours]
       [:> rn/View {:key   (str (random-uuid) "-time-indicator")
                    :style (-> (tw "absolute w-full ml-1")
                               (merge {:top top}))}
        [:> paper/Divider]
        [:> paper/Text {:style {:color (-> theme
                                           (j/get :colors)
                                           (j/get :disabled))}}
         val]])]))
