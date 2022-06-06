(ns app.components.session-ishes
  (:require
   ["react-native" :as rn]
   ["react-native-gesture-handler" :as g]
   ["react-native-paper" :as paper]

   [applied-science.js-interop :as j]

   [app.misc :refer [<sub
                     >evt
                     get-theme
                     active-gesture?]]
   [app.screens.core :refer [screens]]
   [app.tailwind :refer [tw]]
   [potpuri.core :as p]))

(defn component
  "handlers are wrapped with partial to inject `is-selected` and `id` as first args"
  [{:keys [long-press-handler button-handler session-ishes]}]
  (let [theme         (->> [:theme] <sub get-theme)
        border-radius (-> theme (j/get :roundness))]

    [:> rn/View {:style (tw "ml-20")}
     (for [{:session-ish-render/keys [left
                                      id
                                      top
                                      height
                                      width
                                      ;; elevation
                                      color-hex
                                      ripple-color-hex
                                      text-color-hex
                                      label
                                      is-selected
                                      is-tracking
                                      start-label
                                      stop-label]}
           session-ishes]

       [:> rn/View {:key (str id "-session")}

        (when is-selected
          [:> rn/View {:style (merge
                                (tw "absolute flex flex-row items-center")
                                {:top    (-> top (- 2))
                                 :height 2
                                 :left   -50
                                 :right  0})}
           [:> paper/Text start-label]
           [:> rn/View {:style (merge (tw "w-full ml-1")
                                      {:height           2
                                       :background-color (-> theme (j/get :colors) (j/get :text))}) }]])

        (when is-selected
          [:> rn/View {:style (merge
                                (tw "absolute flex flex-row items-center")
                                {:top    (-> top (+ height) (+ 2))
                                 :height 2
                                 :left   -50
                                 :right  0})}
           [:> paper/Text stop-label]
           [:> rn/View {:style (merge (tw "w-full ml-1")
                                      {:height           2
                                       :background-color (-> theme (j/get :colors) (j/get :text))}) }]])

        [:> g/LongPressGestureHandler
         {:min-duration-ms         800
          :on-handler-state-change (partial long-press-handler is-selected id)}


         [:> rn/View {:style (merge
                               (tw "absolute")
                               {:top    top
                                :left   left
                                :height height
                                :width  width})}
          [:> g/RectButton {:style          (-> (tw "h-full w-full")
                                                (merge {:background-color color-hex}
                                                       (when (not is-selected)
                                                         {:border-radius border-radius})))
                            :on-press       (partial button-handler is-selected id)
                            :ripple-color   ripple-color-hex
                            :underlay-color ripple-color-hex
                            :active-opacity 0.7}
           [:> rn/View {:style (tw "h-full w-full overflow-hidden p-1")}
            [:> paper/Text {:style {:color text-color-hex}} label]]]]]])]))
