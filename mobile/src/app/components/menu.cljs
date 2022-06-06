(ns app.components.menu
  (:require
   ["react-native-paper" :as paper]))

(defn button [{:keys [button-color toggle-menu]}]
  [:> paper/IconButton {:icon     "menu"
                        :color    button-color
                        :size     20
                        :on-press toggle-menu}])
