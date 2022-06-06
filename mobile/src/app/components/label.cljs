(ns app.components.label
  (:require
   ["react-native-paper" :as paper]

   [app.tailwind :refer [tw]]))

(defn component
  [{:keys [label update-fn]}]
  [:> paper/TextInput {:default-value  label
                       :style          (tw "mb-8")
                       :on-change-text update-fn}])
