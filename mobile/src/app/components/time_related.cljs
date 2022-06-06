(ns app.components.time-related
  (:require
   ["react-native-paper" :as paper]

   [app.misc :refer [>evt >evt-sync]]
   [app.tailwind :refer [tw]]))

(defn date-button
  [{:keys [value id label field-key dtp-id session-ish-id-key]}]
  [:> paper/Button {:mode     "flat"
                    :icon     "calendar"
                    :style    (tw "mr-4 mt-4 w-40")
                    :on-press #(>evt [:set-date-time-picker
                                      {:date-time-picker/value     value
                                       :date-time-picker/mode      "date"
                                       :date-time-picker/id        dtp-id
                                       session-ish-id-key          id
                                       :date-time-picker/field-key field-key
                                       :date-time-picker/visible   true}])} label])

(defn time-button
  [{:keys [value id label field-key dtp-id session-ish-id-key]}]
  [:> paper/Button {:mode     "flat"
                    :icon     "clock"
                    :style    (tw "mr-4 mt-4 w-28")
                    :on-press #(>evt [:set-date-time-picker
                                      {:date-time-picker/value     value
                                       :date-time-picker/mode      "time"
                                       :date-time-picker/id        dtp-id
                                       session-ish-id-key          id
                                       :date-time-picker/field-key field-key
                                       :date-time-picker/visible   true}])} label])

(defn no-stamp-button
  [{:keys [id set-start set-stop]
    :or   {set-start false
           set-stop  false}}]
  [:> paper/Text "missing required timestamp value"])
