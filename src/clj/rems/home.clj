(ns rems.home
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.test :refer [deftest is]]
            [compojure.core :refer [GET defroutes routes]]
            [markdown.core :as md]
            [rems.auth.util :as auth-util]
            [rems.common-util :refer [index-by]]
            [rems.config :refer [env]]
            [rems.context :as context]
            [rems.css.styles :as styles]
            [rems.db.catalogue :as catalogue]
            [rems.layout :as layout]
            [ring.util.response :refer [content-type not-found redirect response]]))

(defn- apply-for-resource [resource]
  (let [items (->> (catalogue/get-localized-catalogue-items {:resource resource})
                   (filter :enabled))]
    (cond
      (= 0 (count items)) (-> (not-found "Resource not found")
                              (content-type "text/plain"))
      (< 1 (count items)) (-> (not-found "Resource ID is not unique")
                              (content-type "text/plain"))
      :else (redirect (str "/#/application?items=" (:id (first items)))))))

(defn- find-allowed-markdown-file [filename]
  (let [allowed-files (index-by [:file] (filter :file (:extra-pages @env)))]
    (when (contains? allowed-files filename)
      (allowed-files filename))))

(defn- markdown-page [filename]
  (if-let [allowed-file (find-allowed-markdown-file filename)]
    (layout/render filename (md/md-to-html-string (slurp (:file allowed-file))))
    (auth-util/throw-unauthorized)))

(defn render-css
  "Helper function for rendering styles that has parameters for
  easy memoization purposes."
  [language]
  (log/info (str "Rendering stylesheet for language " language))
  (-> (styles/screen-css)
      (response)
      (content-type "text/css")))

(def memoized-render-css (memoize render-css))

(defroutes normal-routes
  (GET "/" [] (layout/home-page))
  (GET "/accept-invitation" {{:keys [token]} :params} (redirect (str "/#/application/accept-invitation/" token)))
  (GET "/apply-for" {{:keys [resource]} :params} (apply-for-resource resource))
  (GET "/landing_page" req (redirect "/#/redirect")) ; DEPRECATED: legacy url redirect
  (GET "/markdown/:filename" [filename] (markdown-page filename))
  (GET "/favicon.ico" [] (redirect "/img/favicon.ico")))

(defroutes css-routes
  (GET "/css/:language/screen.css" [language]
    (binding [context/*lang* (keyword language)]
      (memoized-render-css context/*lang*))))

(defn home-routes []
  (routes normal-routes
          css-routes))
