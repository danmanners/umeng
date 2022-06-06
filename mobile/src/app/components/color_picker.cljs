(ns app.components.color-picker
  (:require
   ["react-native" :as rn]
   ["react-native-color-picker" :as c]
   ["react-native-gesture-handler" :as g]
   ["react-native-paper" :as paper]

   [app.colors :refer [material-500-hexes]]
   [app.misc :refer [<sub >evt]]
   [app.tailwind :refer [tw]]))

(defn component [{:keys [input-color update-fn remove-fn]}]
  (let [{:color-picker/keys [visible value]} (<sub [:color-picker])]
    [:> paper/Portal
     [:> paper/Modal {:visible    visible
                      :on-dismiss #(tap> "dismis color picker")}
      [:> paper/Surface {:style (tw "m-8")}
       [:> rn/View {:style (tw "h-full w-full")}

        [:> paper/IconButton {:icon     "close"
                              :on-press #(>evt [:set-color-picker
                                                #:color-picker {:visible false
                                                                :value   nil}])}]

        [:> rn/View {:style (tw "flex flex-wrap flex-row justify-center m-2")}
         (for [material-color material-500-hexes]
           [:> g/RectButton {:key      (random-uuid)
                             :style    (merge (tw "h-8 w-20") {:background-color material-color})
                             :on-press #(do (update-fn material-color)
                                            (>evt [:set-color-picker
                                                   #:color-picker {:visible false
                                                                   :value   material-color}]))}])]

        [:> paper/Text {:style (tw "text-center mt-4")} "Tap right side of circle to save"]
        [:> c/ColorPicker {:on-color-selected #(do (update-fn %)
                                                   (>evt [:set-color-picker
                                                          #:color-picker {:visible false
                                                                          :value   %}]))
                           :old-color         input-color
                           :default-color     value
                           :style             (tw "flex flex-1 m-4")}]

        [:> paper/Button {:mode     "contained"
                          :icon     "water-off"
                          :style    (tw "m-4")
                          :on-press #(do (remove-fn)
                                         (>evt [:set-color-picker
                                                #:color-picker {:visible false
                                                                :value   nil}]))}
         "Remove color"]]]]]))
