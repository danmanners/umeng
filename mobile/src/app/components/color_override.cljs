(ns app.components.color-override
  (:require
   ["react-native" :as rn]
   ["react-native-paper" :as paper]

   [app.components.color-picker :as color-picker]
   [app.misc :refer [>evt]]
   [app.tailwind :refer [tw]]))

(defn component [{session-ish-color :color
                  color-override    :color-override
                  update-fn         :update-fn
                  remove-fn         :remove-fn}]
  (let [mode  (if (and (some? session-ish-color)
                       color-override) "contained" "flat")
        label (if (some? session-ish-color) session-ish-color "set color")]

    [:> rn/View {:style (tw "flex flex-col mb-8")}
     [:> paper/Button {:mode     mode
                       :icon     "palette"
                       :color    session-ish-color
                       :on-press #(>evt [:set-color-picker
                                         #:color-picker {:visible true
                                                         :value   session-ish-color}])}
      label]

     [color-picker/component {:input-color session-ish-color
                              :update-fn   update-fn
                              :remove-fn   remove-fn}]]))
