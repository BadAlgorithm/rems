(ns rems.api.testing
  "Shared code for API testing"
  (:require [cheshire.core :refer [parse-stream]]
            [luminus-migrations.core :as migrations]
            [mount.core :as mount]
            [rems.config :refer [env]]
            [rems.db.core :as db]
            [rems.db.test-data :as test-data]
            [rems.test-db :refer [db-each-fixture]]
            [rems.handler :refer :all]))

(defn api-once-fixture [f]
  (mount/start
   #'rems.config/env
   #'rems.locales/translations
   #'rems.db.core/*db*
   #'rems.handler/handler)
  ;; TODO: silence logging somehow?
  (f)
  (mount/stop))

(def api-each-fixture db-each-fixture)

(defn authenticate [request api-key user-id]
  (-> request
      (assoc-in [:headers "x-rems-api-key"] api-key)
      (assoc-in [:headers "x-rems-user-id"] user-id)))

(defn assert-response-is-ok [response]
  (assert response)
  (assert (= 200 (:status response))
          (pr-str {:status (:status response)
                   :body (when-let [body (:body response)]
                           (if (string? body)
                             body
                             (slurp body)))}))
  response)

(defn assert-response-is-server-error? [response]
  (assert (= 500 (:status response))))

(defn response-is-unauthorized? [response]
  (= 401 (:status response)))

(defn response-is-forbidden? [response]
  (= 403 (:status response)))

(defn response-is-not-found? [response]
  (= 404 (:status response)))

(defn coll-is-not-empty? [data]
  (and (coll? data)
       (not (empty? data))))

(defn coll-is-empty? [data]
  (and (coll? data)
       (empty? data)))

(defn read-body [{body :body}]
  (cond
    (nil? body) body
    (string? body) body
    true (parse-stream (clojure.java.io/reader body) true)))

(defn read-ok-body [response]
  (assert-response-is-ok response)
  (read-body response))
