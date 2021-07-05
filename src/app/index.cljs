(ns app.index
  (:require
   ["color" :as color]
   ["expo" :as ex]
   ["expo-constants" :as expo-constants]
   ["react-native" :as rn]
   ["react" :as react]
   ["@react-navigation/native" :as nav]
   ["@react-navigation/drawer" :as d]
   ["@react-navigation/stack" :as s]
   ["react-native-appearance" :as appearance]
   ["react-native-paper" :as paper]

   [applied-science.js-interop :as j]
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [reagent.core :as r]
   [re-frame.core :refer [dispatch-sync]]
   [shadow.expo :as expo]

   [app.fx :refer [!navigation-ref]]
   [app.handlers]
   [app.subscriptions]
   [app.helpers :refer [<sub >evt get-theme]]
   [app.screens.core :refer [screens]]
   [app.screens.day :as day]
   [app.screens.settings :as settings]
   [app.screens.session :as session]
   [app.screens.reports :as reports]
   [app.screens.tags :as tags]))

;; must use defonce and must refresh full app so metro can fill these in
;; at live-reload time `require` does not exist and will cause errors
;; must use path relative to :output-dir
(defonce splash-img (js/require "../assets/shadow-cljs.png"))

(def styles
  ^js (-> {:surface
           {:flex            1
            :justify-content "center"}

           :theme-switch
           {:flex-direction  "row"
            :justify-content "space-between"}}
          (#(cske/transform-keys csk/->camelCase %))
          (clj->js)
          (rn/StyleSheet.create)))

(def drawer (d/createDrawerNavigator))

(def stack (s/createStackNavigator))

(defn drawer-navigator [] (-> drawer (j/get :Navigator)))

(defn stack-navigator [] (-> stack (j/get :Navigator)))

(defn drawer-screen [props] [:> (-> drawer (j/get :Screen)) props])

(defn stack-screen [props] [:> (-> stack (j/get :Screen)) props])

(defn drawer-icon
  ([icon] (drawer-icon icon nil))
  ([icon text-color-hex]
   (fn [props]
     (r/as-element
       [:> paper/IconButton {:icon  icon
                             :color (if (some? text-color-hex)
                                      text-color-hex
                                      (j/get props :color))}]))))

(defn custom-drawer [props]
  (let [theme          (->> [:theme] <sub get-theme)
        text-color-hex (-> theme (j/get :colors) (j/get :text))]

    (r/as-element
      [:> d/DrawerContentScrollView (js->clj props)
       [:> d/DrawerItemList (js->clj props)]
       [:> d/DrawerItem {:label       "Share"
                         :label-style {:color text-color-hex}
                         :icon        (drawer-icon "hamburger" text-color-hex)
                         :on-press    #(tap> "Sharing is caring")}]
       [:> d/DrawerItem {:label       "Contact"
                         :label-style {:color (-> theme (j/get :colors) (j/get :text))}
                         :icon        (drawer-icon "hamburger" text-color-hex)
                         :on-press    #(tap> "Scotty, come in Scotty!")}]])))

(defn root []
  (let [theme           (->> [:theme] <sub get-theme)
        !route-name-ref (clojure.core/atom {})
        drawer-style    {:background-color (-> theme
                                               (j/get :colors)
                                               (j/get :surface)
                                               color
                                               (j/call :lighten 0.25)
                                               (j/call :hex))}]

    ;; TODO justin 2021-01-24 Right now appearance provider doesn't do anything
    ;; As far as I can tell it is just a way to programatically determine user theme settings
    ;; Might use this in the future if I support toggling between light and dark mode
    [:> appearance/AppearanceProvider
     [:> paper/Provider
      {:theme theme}

      [:> nav/NavigationContainer
       {:ref             (fn [el] (reset! !navigation-ref el))
        :on-ready        (fn []
                           (swap! !route-name-ref merge {:current (-> @!navigation-ref
                                                                      (j/call :getCurrentRoute)
                                                                      (j/get :name))}))
        :on-state-change (fn []
                           (let [prev-route-name    (-> @!route-name-ref :current)
                                 current-route-name (-> @!navigation-ref
                                                        (j/call :getCurrentRoute)
                                                        (j/get :name))]
                             (when (not= prev-route-name current-route-name)
                               ;; This is where you can do side effecty things like analytics
                               (>evt [:some-fx-example (str "New screen encountered " current-route-name)]))
                             (swap! !route-name-ref merge {:current current-route-name})))}

       [:> (drawer-navigator) {:drawer-content         custom-drawer
                               :drawer-style           drawer-style
                               :initial-route-name     (:reports screens)
                               :drawer-content-options {:active-tint-color   (-> theme (j/get :colors) (j/get :accent))
                                                        :inactive-tint-color (-> theme (j/get :colors) (j/get :text))}}
        (drawer-screen {:name      (:day screens)
                        :options   {:drawerIcon (drawer-icon "calendar")}
                        :component #(r/as-element
                                      [:> (stack-navigator) {:initial-route-name (:day screens)}
                                       (stack-screen {:name      (:day screens)
                                                      :component (paper/withTheme day/screen)
                                                      :options   {:headerShown false}})
                                       (stack-screen {:name      (:session screens)
                                                      :options   {:headerTintColor (-> theme
                                                                                       (j/get :colors)
                                                                                       (j/get :text))
                                                                  :headerTitleStyle
                                                                  #js {:display "none"}
                                                                  :headerStyle
                                                                  ;; for some reason the :surface color comes out the same as :background when used on paper/Surface
                                                                  ;; when using :background here it has a weird opacity issue or something
                                                                  #js {:backgroundColor (-> theme
                                                                                            (j/get :colors)
                                                                                            (j/get :surface))}}
                                                      :component (paper/withTheme session/screen)})])})
        (drawer-screen {:name      (:reports screens)
                        :options   {:drawerIcon (drawer-icon "hamburger")}
                        :component (paper/withTheme reports/screen)})
        (drawer-screen {:name      (:tags screens)
                        :options   {:drawerIcon (drawer-icon "hamburger")}
                        :component (paper/withTheme tags/screen)})
        (drawer-screen {:name      (:settings screens)
                        :options   {:drawerIcon (drawer-icon "tune")}
                        :component (paper/withTheme settings/screen)})]]]]))

(defn start
  {:dev/after-load true}
  []
  (expo/render-root
    (r/as-element
      [root
       ;; this is to force a re-render on every save
       ;; otherwise components nesteded withins screens don't get re-rendered
       {:x (js/Date.now)}])))

(def version (-> expo-constants
                 (j/get :default)
                 (j/get :manifest)
                 (j/get :version)))

(defn init []
  (dispatch-sync [:initialize-db])
  (dispatch-sync [:set-version version])
  ;; TODO add a set day event for "today"
  (start))
