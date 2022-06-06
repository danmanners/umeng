(ns app.subscriptions-test
  (:require
   [com.rpl.specter :as sp :refer [select-one!]]
   [cljs.test :refer [deftest is testing]]
   [app.subscriptions :as subscriptions]))

(deftest subscription-version
  (is (= (subscriptions/version {:version "0.0.1"} :na) "0.0.1")))

(deftest subscription-theme
  (is (= (subscriptions/theme {:settings {:theme :dark}} :na) :DarkTheme)))

(deftest selected-day
  (is (is (= (subscriptions/selected-day
               {:view {:view/selected-day #time/date "2020-12-28"}} :na)
             #time/date "2020-12-28"))))

(deftest calendar
  (is (= (subscriptions/calendar {:calendar :calendar-here} :na) :calendar-here)))

(deftest sessions
  (is (= (subscriptions/sessions {:sessions :session-here} :na) :session-here)))

(deftest sessions-for-this-day
  (is (= true false)))

(deftest this-day
  ;; TODO adjust test once sub injects "now"
  (is (= (subscriptions/this-day #time/date "2020-12-28" :na)
         {:day-of-week   "MONDAY"
          :day-of-month  "28"
          :year          "2020"
          :month         "DECEMBER"
          :display-year  true
          :display-month true})))
