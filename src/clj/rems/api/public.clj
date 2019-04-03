(ns rems.api.public
  (:require [compojure.api.sweet :refer :all]
            [rems.config :refer [env]]
            [rems.locales :as locales]
            [ring.util.http-response :refer :all]
            [schema.core :as s]))

(s/defschema GetTranslationsResponse
  s/Any)

(s/defschema GetThemeResponse
  s/Any)

(s/defschema ExtraPage
  {s/Keyword s/Any})

(s/defschema GetConfigResponse
  {:authentication s/Keyword
   :alternative-login-url (s/maybe s/Str)
   :extra-pages [ExtraPage]
   :languages [s/Keyword]
   :default-language s/Keyword
   :dev s/Bool})

(def translations-api
  (context "/translations" []
    :tags ["translations"]

    (GET "/" []
      :summary "Get translations"
      :return GetTranslationsResponse
      (ok @locales/translations))))

(def theme-api
  (context "/theme" []
    :tags ["theme"]

    (GET "/" []
      :summary "Get current layout theme"
      :return GetThemeResponse
      (ok (:theme @env)))))

(def config-api
  (context "/config" []
    :tags ["config"]

    (GET "/" []
      :summary "Get configuration that is relevant to UI"
      :return GetConfigResponse
      (ok (select-keys @env [:authentication :alternative-login-url :extra-pages :languages :default-language :dev])))))
