(ns user
  "Userspace functions you can run by default in your local REPL."
  (:require
    [clojure.pprint]
    [clojure.spec.alpha :as s]
    [clojure.tools.namespace.repl :as repl]
    [criterium.core :as c]                                  ;; benchmarking
    [expound.alpha :as expound]
    [integrant.core :as ig]
    [integrant.repl :refer [clear go halt prep init reset reset-all]]
    [integrant.repl.state :as state]
    [kit.api :as kit]
    [lambdaisland.classpath.watch-deps :as watch-deps]      ;; hot loading for deps
    [umeng.backend.core :refer [start-app]]
    ))

;; uncomment to enable hot loading for deps
(watch-deps/start! {:aliases [:dev :test]})

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(add-tap (bound-fn* clojure.pprint/pprint))

(defn dev-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (umeng.backend.config/system-config {:profile :dev})
                                  (ig/prep)))))

(defn test-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (umeng.backend.config/system-config {:profile :test})
                                  (ig/prep)))))

;; Can change this to test-prep! if want to run tests as the test profile in your repl
;; You can run tests in the dev profile, too, but there are some differences between
;; the two profiles.
(dev-prep!)

(repl/set-refresh-dirs "src/clj")

(def refresh repl/refresh)

(comment
  (go)
  (reset)
  (require '[xtdb.api :as xt])
  (let [node (-> integrant.repl.state/system :db.xtdb/node)]
    (-> node (xt/submit-tx [[:xtdb.api/put {:xt/id :hello-4 :a "there"}]]))
    (-> node (xt/submit-tx [[:xtdb.api/put {:xt/id       (.toString (java.util.UUID/randomUUID))
                                            :healthcheck true}]]))
    (-> node xt/status)
    (-> node xt/recent-queries)
    (-> node xt/latest-submitted-tx)
    (-> node (xt/db))
    (-> node (xt/db) (xt/q '{:find [e] :where [[e :xt/id _]]}))
    )
  )
