(ns app.tailwind
  (:require
   ["tailwind-rn" :default tailwind-rn]))

;; TODO justin 2021-01-23 set up customized theme https://github.com/vadimdemedes/tailwind-rn#customization
(defn tw [style-str] (-> style-str
                         tailwind-rn
                         (js->clj :keywordize-keys true)))
