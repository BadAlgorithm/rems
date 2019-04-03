(ns rems.handler
  (:require [clojure.tools.logging :as log]
            [compojure.core :refer [defroutes routes wrap-routes]]
            [compojure.route :as route]
            [mount.extensions.namespace-deps :as mount-nsd]
            [mount.lite :as mount]
            [rems.api :refer [api-routes]]
            [rems.auth.auth :as auth]
            [rems.config :refer [env]]
            [rems.entitlements :as entitlements]
            [rems.home :as home]
            [rems.layout :refer [error-page]]
            [rems.locales] ;; to enable translations
            [rems.middleware :as middleware]
            [rems.poller.email] ;; to enable email polling
            [rems.poller.entitlements] ;; to enable entitlement polling
            [rems.util :refer [never-match-route]]
            [ring.util.response :refer [file-response]]))

(defn init
  "init will be called once when
   app is deployed as a servlet on
   an app server such as Tomcat
   put any initialization code here"
  []
  (doseq [component (:started (mount-nsd/start))]
    (log/info component "started")))

(defn destroy
  "destroy will be called when your application
   shuts down, put any clean up code here"
  []
  (doseq [component (:stopped (mount-nsd/stop))]
    (log/info component "stopped"))
  (shutdown-agents)
  (log/info "Rems has shut down!"))

(defn not-found [req]
  (error-page {:status 404
               :title "Page not found"}))

(defn public-routes []
  (routes
   (home/home-routes)
   (auth/auth-routes)))

(defroutes secured-routes
  entitlements/entitlements-routes)

(defn normal-routes []
  (routes
   (public-routes)
   (wrap-routes #'secured-routes middleware/wrap-restricted)
   #'api-routes))

(defn extra-script-routes [{:keys [root files]}]
  (let [files (set files)]
    (fn [request]
      (when (contains? files (:uri request))
        (file-response (:uri request) {:root root})))))

(defn app-routes []
  (routes
   (extra-script-routes (:extra-scripts @env))
   (normal-routes)
   (if-let [path (:extra-static-resources @env)]
     (route/files "/" {:root path})
     never-match-route)
   (if-let [path (:theme-static-resources @env)]
     (route/files "/" {:root path})
     never-match-route)
   not-found))

;; we use mount to construct the app so that middleware can access mount state
(mount/defstate handler
  :start (middleware/wrap-base (app-routes)))
