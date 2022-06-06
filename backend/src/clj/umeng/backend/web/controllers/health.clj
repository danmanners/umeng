(ns umeng.backend.web.controllers.health
  (:require
   [ring.util.http-response :as http-response]
   [xtdb.api :as xt])
  (:import
    [java.util Date]))

(defn healthcheck!
  [node req]
  (http-response/ok
     {:time         (str (Date. (System/currentTimeMillis)))
      :up-since     (str (Date. (.getStartTime (java.lang.management.ManagementFactory/getRuntimeMXBean))))
      :app          {:status  "up"
                     :message "'When you know your data can never change out from underneath you, everything is different.' - Rich Hickey"}
      :db-status    (-> node (xt/status))}))
