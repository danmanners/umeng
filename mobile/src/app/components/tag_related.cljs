(ns app.components.tag-related
  (:require
   ["react-native" :as rn]
   ["react-native-paper" :as paper]

   [potpuri.core :as p]

   [app.misc :refer [<sub >evt]]
   [app.tailwind :refer [tw]]))

(defn tag-button
  [{:keys [color label on-press style]}]
  [:> paper/Button
   (merge
     (when (some? on-press)
       {:on-press on-press})
     (when (some? style)
       {:style style})
     {:mode  (if (some? color) "contained" "outlined")
      :color color})
   label])

(defn tag-remove-modal
  "`remove-fn` is wrapped in partial and given tag-id"
  [{:keys [close-fn remove-fn]}]
  (let [{:tag-remove-modal/keys [visible id label color]}
        (<sub [:tag-remove-modal])]

    [:> paper/Portal
     [:> paper/Modal {:visible    visible
                      :on-dismiss close-fn}
      [:> paper/Surface
       [:> rn/View {:style (tw "p-2 flex-col")}
        ;; close button
        [:> paper/IconButton {:icon     "close"
                              :on-press close-fn}]

        [:> paper/Paragraph
         {:style (tw "mb-4")}
         "Are you sure you want to remove this tag?"]


        [tag-button
         (merge {:style (tw "mb-4")}
                (p/map-of color id label))]

        [:> paper/Button {:icon     "close"
                          :mode     "contained"
                          :style    (tw "mb-4")
                          :color    "red"
                          :on-press (partial remove-fn id)}
         "remove it"]]]]]))

(defn tag-add-modal [{:keys [add-fn close-fn]}]
  (let [all-tags                        (<sub [:tags-not-on-selected-session])
        {:tag-add-modal/keys [visible]} (<sub [:tag-add-modal])]

    [:> paper/Portal
     [:> paper/Modal {:visible    visible
                      :on-dismiss close-fn}
      [:> paper/Surface {:style (tw "m-1")}
       [:> rn/ScrollView
        [:> paper/IconButton {:icon     "close"
                              :on-press close-fn}]

        [:> rn/View {:style (tw "flex flex-row flex-wrap items-center p-4")}
         (for [{:tag/keys [label color id]} all-tags]
           [tag-button
            (merge {:key      id
                    :style    (tw "m-2")
                    :on-press (partial add-fn id)}
                   (p/map-of label color id))])]]]]]))

(defn tags-component
  "`add-fn` and `remove-fn` are meant to dispatch the session-ish update given the tag-id"
  [{:keys [tags add-fn remove-fn]}]
  (let [there-are-tags (-> tags count (> 0))]

    [:> rn/View {:style (tw "flex flex-row flex-wrap items-center")}

     (when there-are-tags
       (for [{:tag/keys [label color id]} tags]
         (let [on-press #(>evt [:set-tag-remove-modal
                                #:tag-remove-modal
                                {:visible true
                                 :id      id
                                 :color   color
                                 :label   label}])
               style    (tw "mr-4 mb-4")]
           [tag-button (merge {:key id}
                              (p/map-of color id label on-press style))])))

     [:> paper/Button {:icon     "plus"
                       :mode     "flat"
                       :on-press #(>evt [:set-tag-add-modal
                                         #:tag-add-modal
                                         {:visible true}])}
      "Add tag"]

     [tag-remove-modal
      {:close-fn #(>evt [:set-tag-remove-modal
                         #:tag-remove-modal {:visible false
                                             :id      nil
                                             :label   nil}])

       ;; I'm not sure how I feel about doubling events and also injecting the update function
       :remove-fn (fn [tag-id]
                    (>evt [:set-tag-remove-modal
                           #:tag-remove-modal
                           {:visible false
                            :id      nil
                            :label   nil}])
                    (remove-fn tag-id))}]

     [tag-add-modal {:close-fn #(>evt [:set-tag-add-modal
                                       #:tag-add-modal {:visible false}])
                     :add-fn   (fn [tag-id]
                                 (>evt [:set-tag-add-modal
                                        #:tag-add-modal {:visible false}])
                                 (add-fn tag-id))}]]))
