(ns rems.poller.email
  "Sending emails based on application events."
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [mount.lite :as mount]
            [postal.core :as postal]
            [rems.config :refer [env]]
            [rems.db.applications :as applications]
            [rems.db.users :as users]
            [rems.text :refer [text text-format with-language]]
            [rems.poller.common :as common]
            [rems.util :as util]
            [rems.workflow.dynamic :as dynamic]))

;;; Mapping events to emails

;; TODO list of resources?
;; TODO use real name when addressing user?

;; move this to a util namespace if its needed somewhere else
(defn- link-to-application [application-id]
  (str (:public-url @env) "#/application/" application-id))

(defn- invitation-link [token]
  (str (:public-url @env) "accept-invitation?token=" token))

(defmulti ^:private event-to-emails-impl
  (fn [event _application] (:event/type event)))

(defmethod event-to-emails-impl :default [_event _application]
  [])

;; There's a slight inconsistency here: we look at current members, so
;; a member might get an email for an event that happens before he was
;; added.
(defmethod event-to-emails-impl :application.event/approved [event application]
  (vec
   (for [member (:members application)] ;; applicant is a member
     {:to-user (:userid member)
      :subject (text :t.email.application-approved/subject)
      :body (text-format :t.email.application-approved/message
                         (:userid member)
                         (:application/id event)
                         (link-to-application (:application/id event)))})))

(defmethod event-to-emails-impl :application.event/rejected [event application]
  (vec
   (for [member (:members application)] ;; applicant is a member
     {:to-user (:userid member)
      :subject (text :t.email.application-rejected/subject)
      :body (text-format :t.email.application-rejected/message
                         (:userid member)
                         (:application/id event)
                         (link-to-application (:application/id event)))})))

(defmethod event-to-emails-impl :application.event/closed [event application]
  (vec
   (for [member (:members application)] ;; applicant is a member
     {:to-user (:userid member)
      :subject (text :t.email.application-closed/subject)
      :body (text-format :t.email.application-closed/message
                         (:userid member)
                         (:application/id event)
                         (link-to-application (:application/id event)))})))

(defmethod event-to-emails-impl :application.event/comment-requested [event _application]
  (vec
   (for [commenter (:application/commenters event)]
     {:to-user commenter
      :subject (text :t.email.comment-requested/subject)
      :body (text-format :t.email.comment-requested/message
                         commenter
                         (:event/actor event)
                         (:application/id event)
                         (link-to-application (:application/id event)))})))

(defmethod event-to-emails-impl :application.event/decision-requested [event _application]
  (vec
   (for [decider (:application/deciders event)]
     {:to-user decider
      :subject (text :t.email.decision-requested/subject)
      :body (text-format :t.email.decision-requested/message
                         decider
                         (:event/actor event)
                         (:application/id event)
                         (link-to-application (:application/id event)))})))

(defmethod event-to-emails-impl :application.event/commented [event application]
  (vec
   (for [handler (get-in application [:workflow :handlers])]
     {:to-user handler
      :subject (text :t.email.commented/subject)
      :body (text-format :t.email.commented/message
                         handler
                         (:event/actor event)
                         (:application/id event)
                         (link-to-application (:application/id event)))})))

(defmethod event-to-emails-impl :application.event/decided [event application]
  (vec
   (for [handler (get-in application [:workflow :handlers])]
     {:to-user handler
      :subject (text :t.email.decided/subject)
      :body (text-format :t.email.decided/message
                         handler
                         (:event/actor event)
                         (:application/id event)
                         (link-to-application (:application/id event)))})))

(defmethod event-to-emails-impl :application.event/member-added [event _application]
  ;; TODO email to applicant? email to handler?
  [{:to-user (:userid (:application/member event))
    :subject (text :t.email.member-added/subject)
    :body (text-format :t.email.member-added/message
                       (:userid (:application/member event))
                       (:application/id event)
                       (link-to-application (:application/id event)))}])

(defmethod event-to-emails-impl :application.event/member-invited [event _application]
  [{:to (:email (:application/member event))
    :subject (text :t.email.member-invited/subject)
    :body (text-format :t.email.member-invited/message
                       (:email (:application/member event))
                       (invitation-link (:invitation/token event)))}])

;; TODO member-joined?

(defn event-to-emails [event]
  (when-let [app-id (:application/id event)]
    ;; TODO use api-get-application-v2 or similar
    (event-to-emails-impl event (applications/get-application-state app-id))))

;;; Generic poller infrastructure

;;; Email poller

;; You can test email sending by:
;;
;; 1. running mailhog: docker run -p 1025:1025 -p 8025:8025 mailhog/mailhog
;; 2. adding {:mail-from "rems@example.com" :smtp-host "localhost" :smtp-port 1025} to dev-config.edn
;; 3. generating some emails
;;    - you can reset the email poller state with (common/set-poller-state! :rems.poller.email/poller nil)
;; 4. open http://localhost:8025 in your browser to view the emails

(defn mark-all-emails-as-sent! []
  (let [events (applications/get-dynamic-application-events-since 0)
        last-id (:event/id (last events))]
    (common/set-poller-state! ::poller {:last-processed-event-id last-id})))

(defn send-email! [email-spec]
  (let [host (:smtp-host @env)
        port (:smtp-port @env)]
    (if (not (and host port))
      (log/info "pretending to send email:" (pr-str email-spec))
      (let [email (assoc email-spec
                         :from (:mail-from @env)
                         :body (str (:body email-spec)
                                    (text :t.email/footer))
                         :to (or (:to email-spec)
                                 (util/get-user-mail
                                  (users/get-user-attributes
                                   (:to-user email-spec)))))]
        ;; TODO check that :to is set
        (log/info "sending email:" (pr-str email))
        (postal/send-message {:host host :port port} email)))))

(defn run []
  (common/run-event-poller ::poller (fn [event]
                                      (with-language (:default-language @env)
                                        #(doseq [mail (event-to-emails event)]
                                           (send-email! mail))))))

(mount/defstate email-poller
  :start (doto (java.util.concurrent.ScheduledThreadPoolExecutor. 1)
           (.scheduleWithFixedDelay run 10 10 java.util.concurrent.TimeUnit/SECONDS))
  :stop (doto @email-poller
          (.shutdown)
          (.awaitTermination 60 java.util.concurrent.TimeUnit/SECONDS)))

(comment
  (mount/start #{#'email-poller})
  (mount/stop #{#'email-poller}))
