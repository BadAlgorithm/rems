(ns ^:browser rems.test-browser
  (:require [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [etaoin.api :refer :all]
            [luminus-migrations.core :as migrations]
            [mount.core :as mount]
            [rems.config]
            [rems.db.test-data :as test-data]
            [rems.standalone])
  (:import (java.net SocketException)))

(def ^:private +test-url+ "http://localhost:3001/")

(def ^:dynamic *driver*)

(def reporting-dir (doto (io/file "browsertest-errors")
                     (.mkdirs)))

(defn fixture-driver
  "Executes a test running a driver.
   Bounds a driver with the global *driver* variable."
  [f]
  ;; TODO: these args don't affect the date format of <input type="date"> elements; figure out a reliable way to set it
  (let [run #(with-chrome-headless {:args ["--lang=en-US"]
                                    :prefs {"intl.accept_languages" "en-US"}}
                                   driver
               (binding [*driver* driver]
                 (f)))]
    (try
      (run)
      (catch SocketException e
        (log/warn e "WebDriver failed to start, retrying...")
        (run)))))

(defn fixture-standalone [f]
  (mount/start)
  (f)
  (mount/stop))

(defn smoke-test [f]
  (let [response (http/get (str +test-url+ "js/app.js"))]
    (assert (= 200 (:status response))
            (str "Failed to load app.js: " response))
    (f)))

(use-fixtures
  :each ;; start and stop driver for each test
  fixture-driver)

(use-fixtures
  :once
  fixture-standalone
  smoke-test)

;;; basic navigation

(defn login-as [username]
  (doto *driver*
    (set-window-size 1400 7000) ;; big enough to show the whole page without scrolling
    (go +test-url+)
    (screenshot (io/file reporting-dir "landing-page.png"))
    (click-visible {:class "login-btn"})
    (screenshot (io/file reporting-dir "login-page.png"))
    (click-visible [{:class "users"} {:tag :a, :fn/text username}])
    (wait-visible :logout)))

(defn- wait-page-loaded []
  (wait-invisible *driver* {:css ".fa-spinner"}))

(defn click-navigation-menu [link-text]
  (click-visible *driver* [:big-navbar {:tag :a, :fn/text link-text}]))

(defn go-to-catalogue []
  (click-navigation-menu "Catalogue")
  (wait-visible *driver* {:tag :h2, :fn/text "Catalogue"})
  (wait-page-loaded))

(defn go-to-applications []
  (click-navigation-menu "Applications")
  (wait-visible *driver* {:tag :h2, :fn/text "Applications"})
  (wait-visible *driver* [{:css "i.fa-search"}])
  (wait-page-loaded))

;;; catalogue page

(defn add-to-cart [resource-name]
  (with-wait 30
    (click-visible *driver* [{:css "table.catalogue"}
                             {:fn/text resource-name}
                             {:xpath "./ancestor::tr"}
                             {:css "button.add-to-cart"}])))

(defn apply-for-resource [resource-name]
  (click-visible *driver* [{:css "table.cart"}
                           {:fn/text resource-name}
                           {:xpath "./ancestor::tr"}
                           {:css "button.apply-for-catalogue-items"}])
  (wait-visible *driver* {:tag :h2, :fn/text "Application"})
  (wait-page-loaded))

;;; application page

(defn fill-form-field [label text]
  (let [id (get-element-attr *driver* [:form
                                       {:tag :label, :fn/text label}]
                             :for)]
    ;; XXX: need to use `fill-human`, because `fill` is so quick that the form drops characters here and there
    (fill-human *driver* {:id id} text)))

(defn set-date [label date]
  (let [id (get-element-attr *driver* [:form
                                       {:tag :label, :fn/text label}]
                             :for)]
    ;; XXX: The date format depends on operating system settings and is unaffected by browser locale,
    ;;      so we cannot reliably know the date format to type into the date field and anyways WebDriver
    ;;      support for date fields seems buggy. Changing the field with JavaScript is more reliable.
    (js-execute *driver*
                ;; XXX: React workaround for dispatchEvent, see https://github.com/facebook/react/issues/10135
                "
                function setNativeValue(element, value) {
                    const { set: valueSetter } = Object.getOwnPropertyDescriptor(element, 'value') || {}
                    const prototype = Object.getPrototypeOf(element)
                    const { set: prototypeValueSetter } = Object.getOwnPropertyDescriptor(prototype, 'value') || {}

                    if (prototypeValueSetter && valueSetter !== prototypeValueSetter) {
                        prototypeValueSetter.call(element, value)
                    } else if (valueSetter) {
                        valueSetter.call(element, value)
                    } else {
                        throw new Error('The given element does not have a value setter')
                    }
                }
                var field = document.getElementById(arguments[0])
                setNativeValue(field, arguments[1])
                field.dispatchEvent(new Event('change', {bubbles: true}))
                "
                id date)))

(defn select-option [label option]
  (let [id (get-element-attr *driver* [:form
                                       {:tag :label, :fn/text label}]
                             :for)]
    (fill *driver* {:id id} option)))

(defn check-box [value]
  ;; XXX: assumes that the checkbox is unchecked
  (click-visible *driver* [{:css (str "input[value='" value "']")}]))

(defn accept-license [label]
  ;; XXX: assumes that the checkbox is unchecked
  (click-visible *driver* [:licenses
                           {:tag :a, :fn/text label}
                           {:xpath "./ancestor::div[@class='license']"}
                           {:css "input[type='checkbox']"}]))

(defn send-application []
  (doto *driver*
    (click-visible :submit)
    (wait-visible  :status-success)
    (click-visible :modal-ok)
    (wait-has-class :apply-phase "completed")))

(defn get-application-id []
  (last (str/split (get-url *driver*) #"/")))

;; applications page

(defn get-application-summary [application-id]
  (let [row (query *driver* [{:css "table.applications"}
                             {:tag :tr, :id (str "application-" application-id)}])]
    {:id application-id
     :description (get-element-text-el *driver* (child *driver* row {:css ".description"}))
     :resource (get-element-text-el *driver* (child *driver* row {:css ".resource"}))
     :applicant (get-element-text-el *driver* (child *driver* row {:css ".applicant"}))
     :state (get-element-text-el *driver* (child *driver* row {:css ".state"}))}))

;;; tests

(deftest test-new-application
  (with-postmortem *driver* {:dir reporting-dir}
    (login-as "developer")

    (go-to-catalogue)
    (add-to-cart "THL catalogue item")
    (apply-for-resource "THL catalogue item")

    (fill-form-field "Application title" "Test name")
    (fill-form-field "1. Research project full title" "Test")
    (select-option "2. This is an amendment of a previous approved application" "y")
    (fill-form-field "3. Study PIs (name, titile, affiliation, email)" "Test")
    (set-date "5. Research project start date" "2050-01-01")
    (set-date "6. Research project end date" "2050-12-31")
    (fill-form-field "7. Describe in detail the aims of the study and analysis plan" "Test")
    (fill-form-field "9. Public description of the project (in Finnish, when possible), to be published in THL Biobank." "Test")
    (fill-form-field "10. Place/plces of research, including place of sample and/or data analysis." "Test")
    (fill-form-field "11. Description of other research group members and their role in the applied project." "Test")
    (fill-form-field "12. Specify selection criteria of study participants (if applicable)" "Test")
    (fill-form-field "13. Specify requested phenotype data (information on variables is found at https://kite.fimm.fi)" "Test")
    (select-option "16. Are biological samples requested?" "n")
    (fill-form-field "17. What study results will be returned to THL Biobank (if any)?" "Test")
    (fill-form-field "18. Ethical aspects of the project" "Test")
    (fill-form-field "19. Project keywords (max 5)" "Test")
    (fill-form-field "20. Planned publications (max 3)" "Test")
    (fill-form-field "21. Funding information" "Test")
    (fill-form-field "22. Invoice address (Service prices: www.thl.fi/biobank/researchers)" "Test")
    (check-box "disease_prevention")
    (accept-license "CC Attribution 4.0")
    (accept-license "General Terms of Use")

    (send-application)
    (is (= "State: Applied" (get-element-text *driver* :application-state)))

    (let [application-id (get-application-id)]
      (go-to-applications)
      (let [summary (get-application-summary application-id)]
        (is (= "THL catalogue item" (:resource summary)))
        (is (= "developer" (:applicant summary)))
        (is (= "Applied" (:state summary)))
        ;; don't bother trying to predict the external id:
        (is (.contains (:description summary) "Test name"))))))

(deftest test-guide-page
  (with-postmortem *driver* {:dir reporting-dir}
    (go *driver* (str +test-url+ "#/guide"))
    ;; if there is a js exception, nothing renders, so let's check
    ;; that we have lots of examples in the dom:
    (is (< 60 (count (query-all *driver* {:class :example}))))))
