(ns rems.db.core
  {:ns-tracker/resource-deps ["sql/queries.sql"]}
  (:require [clj-time.core :as time]
            [clj-time.jdbc] ;; convert db timestamps to joda-time objects
            [clojure.set :refer [superset?]]
            [conman.core :as conman]
            [mount.lite :as mount]
            [rems.config :refer [env]]))

(mount/defstate db-connection
  :start (cond
           (:database-url @env) (conman/connect! {:jdbc-url (:database-url @env)})
           (:database-jndi-name @env) {:name (:database-jndi-name @env)}
           :else (throw (IllegalArgumentException. ":database-url or :database-jndi-name must be configured")))
  :stop (conman/disconnect! @db-connection))

(def ^:dynamic *db* nil)

(conman/bind-connection *db* "sql/queries.sql")

(defn assert-test-database! []
  (assert (= {:current_database "rems_test"}
             (get-database-name))))

(defn contains-all-kv-pairs? [supermap map]
  (superset? (set supermap) (set map)))

(defn apply-filters [filters coll]
  (let [filters (or filters {})]
    (filter #(contains-all-kv-pairs? % filters) coll)))

(defn now-active?
  ([start end]
   (now-active? (time/now) start end))
  ([now start end]
   (and (or (nil? start)
            (time/after? now start))
        (or (nil? end)
            (time/before? now end)))))

(defn assoc-active
  "Calculates and assocs :active attribute based on current time and :start and :endt attributes.

   Current time can be passed in optionally."
  ([x]
   (assoc-active (time/now) x))
  ([now x]
   ;; TODO: rename endt to end in all places
   (assoc x :active (now-active? now (:start x) (or (:endt x)
                                                    (:end x))))))
