(ns ^:integration rems.api.test-applications
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [rems.api.testing :refer :all]
            [rems.db.form :as form]
            [rems.handler :refer [handler]]
            [ring.mock.request :refer :all]))

(use-fixtures :once api-once-fixture)
(use-fixtures :each api-each-fixture)

;;; shared helpers

(defn- create-dynamic-workflow []
  (-> (request :post "/api/workflows/create")
      (json-body {:organization "abc"
                  :title "dynamic workflow"
                  :type :dynamic
                  :handlers ["developer"]})
      (authenticate "42" "owner")
      (@handler)
      read-ok-body
      :id))

(defn- create-form-with-fields [form-items]
  (-> (request :post "/api/forms/create")
      (authenticate "42" "owner")
      (json-body {:organization "abc"
                  :title ""
                  :items form-items})
      (@handler)
      read-ok-body
      :id))

(defn- create-empty-form []
  (create-form-with-fields []))

(defn- create-catalogue-item [form-id workflow-id]
  (-> (request :post "/api/catalogue-items/create")
      (authenticate "42" "owner")
      (json-body {:title ""
                  :form form-id
                  :resid 1
                  :wfid workflow-id
                  :state "enabled"})
      (@handler)
      read-ok-body
      :id))

(defn- create-dymmy-catalogue-item []
  (let [form-id (create-empty-form)
        workflow-id (create-dynamic-workflow)]
    (create-catalogue-item form-id workflow-id)))

(defn- send-dynamic-command [actor cmd]
  (-> (request :post "/api/applications/command")
      (authenticate "42" actor)
      (json-body cmd)
      (@handler)
      read-body))

;; TODO refactor tests to use true v2 api
(defn- get-application [actor id]
  (-> (request :get (str "/api/v2/applications/" id "/migration"))
      (authenticate "42" actor)
      (@handler)
      read-body))

(defn- create-v2-application [catalogue-item-ids user-id]
  (-> (request :post "/api/v2/applications/create")
      (authenticate "42" user-id)
      (json-body {:catalogue-item-ids catalogue-item-ids})
      (@handler)
      read-ok-body
      :application-id))

(defn- create-dynamic-application [user-id]
  (create-v2-application [(create-dymmy-catalogue-item)] user-id))

(defn- get-ids [applications]
  (set (map :application/id applications)))

(defn- get-v2-applications [user-id]
  (-> (request :get "/api/v2/applications")
      (authenticate "42" user-id)
      (@handler)
      read-ok-body))

(defn- get-v2-application [app-id user-id]
  (-> (request :get (str "/api/v2/applications/" app-id))
      (authenticate "42" user-id)
      (@handler)
      read-ok-body))

(defn- get-v2-open-reviews [user-id]
  (-> (request :get "/api/v2/reviews/open")
      (authenticate "42" user-id)
      (@handler)
      read-ok-body))

(defn- get-v2-handled-reviews [user-id]
  (-> (request :get "/api/v2/reviews/handled")
      (authenticate "42" user-id)
      (@handler)
      read-ok-body))

;;; tests

(defn- strip-cookie-attributes [cookie]
  (re-find #"[^;]*" cookie))

(defn- get-csrf-token [response]
  (let [token-regex #"var csrfToken = '([^\']*)'"
        [_ token] (re-find token-regex (:body response))]
    token))

(deftest application-api-session-test
  (let [username "alice"
        login-headers (-> (request :get "/Shibboleth.sso/Login" {:username username})
                          (@handler)
                          :headers)
        cookie (-> (get login-headers "Set-Cookie")
                   first
                   strip-cookie-attributes)
        csrf (-> (request :get "/")
                 (header "Cookie" cookie)
                 (@handler)
                 get-csrf-token)
        cat-id (create-dymmy-catalogue-item)]
    (is cookie)
    (is csrf)
    (testing "save with session"
      (let [body (-> (request :post "/api/v2/applications/create")
                     (header "Cookie" cookie)
                     (header "x-csrf-token" csrf)
                     (json-body {:catalogue-item-ids [cat-id]})
                     (@handler)
                     assert-response-is-ok
                     read-body)]
        (is (:success body))))
    (testing "save with session but without csrf"
      (let [response (-> (request :post "/api/v2/applications/create")
                         (header "Cookie" cookie)
                         (json-body {:catalogue-item-ids [cat-id]})
                         (@handler))]
        (is (response-is-unauthorized? response))))
    (testing "save with session and csrf and wrong api-key"
      (let [response (-> (request :post "/api/v2/applications/create")
                         (header "Cookie" cookie)
                         (header "x-csrf-token" csrf)
                         (header "x-rems-api-key" "WRONG")
                         (json-body {:catalogue-item-ids [cat-id]})
                         (@handler))
            body (read-body response)]
        (is (response-is-unauthorized? response))
        (is (= "invalid api key" body))))))

(deftest pdf-smoke-test
  (testing "not found"
    (let [response (-> (request :get "/api/applications/9999999/pdf")
                       (authenticate "42" "developer")
                       (@handler))]
      (is (response-is-not-found? response))))
  (testing "forbidden"
    (let [response (-> (request :get "/api/applications/13/pdf")
                       (authenticate "42" "bob")
                       (@handler))]
      (is (response-is-forbidden? response))))
  (testing "success"
    (let [response (-> (request :get "/api/applications/13/pdf")
                       (authenticate "42" "developer")
                       (@handler)
                       assert-response-is-ok)]
      (is (= "application/pdf" (get-in response [:headers "Content-Type"])))
      (is (.startsWith (slurp (:body response)) "%PDF-1.")))))

(deftest dynamic-applications-test
  (let [user-id "alice"
        handler-id "developer"
        commenter-id "carl"
        decider-id "bob"
        application-id 11] ;; submitted dynamic application from test data

    (testing "getting dynamic application as applicant"
      (let [data (get-application user-id application-id)]
        (is (= "workflow/dynamic" (get-in data [:application :workflow :type])))
        (is (= ["application.event/created"
                "application.event/draft-saved"
                "application.event/submitted"]
               (map :event/type (get-in data [:application :dynamic-events]))))
        (is (= ["rems.workflow.dynamic/remove-member"
                "rems.workflow.dynamic/uninvite-member"]
               (get-in data [:application :possible-commands])))))

    (testing "getting dynamic application as handler"
      (let [data (get-application handler-id application-id)]
        (is (= "workflow/dynamic" (get-in data [:application :workflow :type])))
        (is (= #{"rems.workflow.dynamic/request-comment"
                 "rems.workflow.dynamic/request-decision"
                 "rems.workflow.dynamic/reject"
                 "rems.workflow.dynamic/approve"
                 "rems.workflow.dynamic/return"
                 "rems.workflow.dynamic/add-member"
                 "rems.workflow.dynamic/remove-member"
                 "rems.workflow.dynamic/invite-member"
                 "rems.workflow.dynamic/uninvite-member"
                 "see-everything"}
               (set (get-in data [:application :possible-commands]))))))

    (testing "send command without user"
      (is (= {:success false
              :errors [{:type "forbidden"}]}
             (send-dynamic-command "" {:type :rems.workflow.dynamic/approve
                                       :application-id application-id}))
          "user should be forbidden to send command"))

    (testing "send command with a user that is not a handler"
      (is (= {:success false
              :errors [{:type "forbidden"}]}
             (send-dynamic-command user-id {:type :rems.workflow.dynamic/approve
                                            :application-id application-id
                                            :comment ""}))
          "user should be forbidden to send command"))

    (testing "send commands with authorized user"
      (testing "even handler cannot comment without request"
        (is (= {:errors [{:type "forbidden"}], :success false}
               (send-dynamic-command handler-id
                                     {:type :rems.workflow.dynamic/comment
                                      :application-id application-id
                                      :comment "What am I commenting on?"}))))
      (testing "comment with request"
        (let [eventcount (count (get-in (get-application handler-id application-id)
                                        [:application :dynamic-events]))]
          (testing "requesting comment"
            (is (= {:success true} (send-dynamic-command handler-id
                                                         {:type :rems.workflow.dynamic/request-comment
                                                          :application-id application-id
                                                          :commenters [decider-id commenter-id]
                                                          :comment "What say you?"}))))
          (testing "commenter can now comment"
            (is (= {:success true} (send-dynamic-command commenter-id
                                                         {:type :rems.workflow.dynamic/comment
                                                          :application-id application-id
                                                          :comment "Yeah, I dunno"}))))
          (testing "comment was linked to request"
            (let [application (get-application handler-id application-id)
                  request-event (get-in application [:application :dynamic-events eventcount])
                  comment-event (get-in application [:application :dynamic-events (inc eventcount)])]
              (is (= (:application/request-id request-event)
                     (:application/request-id comment-event)))))))
      (testing "request-decision"
        (is (= {:success true} (send-dynamic-command handler-id
                                                     {:type :rems.workflow.dynamic/request-decision
                                                      :application-id application-id
                                                      :deciders [decider-id]
                                                      :comment ""}))))
      (testing "decide"
        (is (= {:success true} (send-dynamic-command decider-id
                                                     {:type :rems.workflow.dynamic/decide
                                                      :application-id application-id
                                                      :decision :approved
                                                      :comment ""}))))
      (testing "approve"
        (is (= {:success true} (send-dynamic-command handler-id {:type :rems.workflow.dynamic/approve
                                                                 :application-id application-id
                                                                 :comment ""})))
        (let [handler-data (get-application handler-id application-id)
              handler-event-types (map :event/type (get-in handler-data [:application :dynamic-events]))
              applicant-data (get-application user-id application-id)
              applicant-event-types (map :event/type (get-in applicant-data [:application :dynamic-events]))]
          (testing "handler can see all events"
            (is (= {:id application-id
                    :state "rems.workflow.dynamic/approved"}
                   (select-keys (:application handler-data) [:id :state])))
            (is (= ["application.event/created"
                    "application.event/draft-saved"
                    "application.event/submitted"
                    "application.event/comment-requested"
                    "application.event/commented"
                    "application.event/decision-requested"
                    "application.event/decided"
                    "application.event/approved"]
                   handler-event-types)))
          (testing "applicant cannot see all events"
            (is (= ["application.event/created"
                    "application.event/draft-saved"
                    "application.event/submitted"
                    "application.event/approved"]
                   applicant-event-types))))))))

(deftest dynamic-application-create-test
  (let [api-key "42"
        user-id "alice"
        application-id (create-dynamic-application user-id)]

    (testing "creating"
      (is (some? application-id))
      (let [created (get-application user-id application-id)]
        (is (= "rems.workflow.dynamic/draft" (get-in created [:application :state])))))

    (testing "getting application as other user is forbidden"
      (is (response-is-forbidden?
           (-> (request :get (str "/api/v2/applications/" application-id))
               (authenticate api-key "bob")
               (@handler)))))

    (testing "modifying application as other user is forbidden"
      (is (= {:success false
              :errors [{:type "forbidden"}]}
             (send-dynamic-command "bob" {:type :rems.workflow.dynamic/save-draft
                                          :application-id application-id
                                          :field-values {}
                                          :accepted-licenses #{}}))))

    (testing "submitting"
      (is (= {:success true}
             (send-dynamic-command user-id {:type :rems.workflow.dynamic/submit
                                            :application-id application-id})))
      (let [submitted (get-application user-id application-id)]
        (is (= "rems.workflow.dynamic/submitted" (get-in submitted [:application :state])))
        (is (= ["application.event/created"
                "application.event/submitted"]
               (map :event/type (get-in submitted [:application :dynamic-events]))))))))

(def testfile (clojure.java.io/file "./test-data/test.txt"))

(def malicious-file (clojure.java.io/file "./test-data/malicious_test.html"))

(def filecontent {:tempfile testfile
                  :content-type "text/plain"
                  :filename "test.txt"
                  :size (.length testfile)})

(def malicious-content {:tempfile malicious-file
                        :content-type "text/html"
                        :filename "malicious_test.html"
                        :size (.length malicious-file)})

(deftest application-api-attachments-test
  (let [api-key "42"
        user-id "alice"
        workflow-id (create-dynamic-workflow)
        form-id (create-form-with-fields [{:title {:en "some attachment"}
                                           :type "attachment"
                                           :optional true}])
        field-id (-> (form/get-form form-id) :fields first :id)
        cat-id (create-catalogue-item form-id workflow-id)
        app-id (create-v2-application [cat-id] user-id)]
    (testing "uploading attachment for a draft"
      (-> (request :post (str "/api/applications/add_attachment?application-id=" app-id "&field-id=" field-id))
          (assoc :params {"file" filecontent})
          (assoc :multipart-params {"file" filecontent})
          (authenticate api-key user-id)
          (@handler)
          assert-response-is-ok))
    (testing "uploading malicious file for a draft"
      (let [response (-> (request :post (str "/api/applications/add_attachment?application-id=" app-id "&field-id=" field-id))
                         (assoc :params {"file" malicious-content})
                         (assoc :multipart-params {"file" malicious-content})
                         (authenticate api-key user-id)
                         (@handler))]
        (is (= 400 (:status response)))))
    (testing "retrieving attachment for a draft"
      (let [response (-> (request :get "/api/applications/attachments" {:application-id app-id :field-id field-id})
                         (authenticate api-key user-id)
                         (@handler)
                         assert-response-is-ok)]
        (is (= (slurp testfile) (slurp (:body response))))))
    (testing "uploading attachment as non-applicant"
      (let [response (-> (request :post (str "/api/applications/add_attachment?application-id=" app-id "&field-id=" field-id))
                         (assoc :params {"file" filecontent})
                         (assoc :multipart-params {"file" filecontent})
                         (authenticate api-key "carl")
                         (@handler))]
        (is (response-is-forbidden? response))))
    (testing "retrieving attachment as non-applicant"
      (let [response (-> (request :get "/api/applications/attachments" {:application-id app-id :field-id field-id})
                         (authenticate api-key "carl")
                         (@handler))]
        (is (response-is-forbidden? response))))
    (testing "submit application"
      (is (= {:success true} (send-dynamic-command user-id {:type :rems.workflow.dynamic/submit
                                                            :application-id app-id}))))
    (testing "uploading attachment for a submitted application"
      (let [response (-> (request :post (str "/api/applications/add_attachment?application-id=" app-id "&field-id=" field-id))
                         (assoc :params {"file" filecontent})
                         (assoc :multipart-params {"file" filecontent})
                         (authenticate api-key user-id)
                         (@handler))]
        (is (response-is-forbidden? response))))))

(deftest applications-api-security-test
  (let [cat-id (create-dymmy-catalogue-item)
        app-id (create-v2-application [cat-id] "alice")]
    (testing "fetch application without authentication"
      (is (response-is-unauthorized? (-> (request :get (str "/api/v2/applications/" app-id))
                                         (@handler)))))
    (testing "fetch deciders without authentication"
      (is (response-is-unauthorized? (-> (request :get "/api/applications/deciders")
                                         (@handler)))))
    (testing "create without authentication"
      (is (response-is-unauthorized? (-> (request :post "/api/v2/applications/create")
                                         (json-body {:catalogue-item-ids [cat-id]})
                                         (@handler)))))
    (testing "create with wrong API-Key"
      (is (response-is-unauthorized? (-> (request :post "/api/v2/applications/create")
                                         (assoc-in [:headers "x-rems-api-key"] "invalid-api-key")
                                         (json-body {:catalogue-item-ids [cat-id]})
                                         (@handler)))))
    (testing "send command without authentication"
      (is (response-is-unauthorized? (-> (request :post "/api/applications/command")
                                         (json-body {:type :rems.workflow.dynamic/submit
                                                     :application-id app-id})
                                         (@handler)))))
    (testing "send command with wrong api-key"
      (is (response-is-unauthorized? (-> (request :post "/api/applications/command")
                                         (authenticate "invalid-api-key" "alice")
                                         (json-body {:type :rems.workflow.dynamic/submit
                                                     :application-id app-id})
                                         (@handler)))))
    (testing "upload attachment without authentication"
      (is (response-is-unauthorized? (-> (request :post "/api/applications/add_attachment")
                                         (assoc :params {"file" filecontent})
                                         (assoc :multipart-params {"file" filecontent})
                                         (@handler)))))
    (testing "upload attachment with wrong API-Key"
      (is (response-is-unauthorized? (-> (request :post "/api/applications/add_attachment")
                                         (assoc :params {"file" filecontent})
                                         (assoc :multipart-params {"file" filecontent})
                                         (authenticate "invalid-api-key" "developer")
                                         (@handler)))))))

(deftest test-v2-application-api
  (let [app-id (create-dynamic-application "alice")]

    (testing "list user applications"
      (is (contains? (get-ids (get-v2-applications "alice"))
                     app-id)))

    (testing "get single application"
      (is (= app-id
             (:application/id (get-v2-application app-id "alice")))))))

(deftest test-v2-review-api
  (let [app-id (create-dynamic-application "alice")]

    (testing "does not list drafts"
      (is (not (contains? (get-ids (get-v2-open-reviews "developer"))
                          app-id))))

    (testing "lists submitted in open reviews"
      (is (= {:success true} (send-dynamic-command "alice" {:type :rems.workflow.dynamic/submit
                                                            :application-id app-id})))
      (is (contains? (get-ids (get-v2-open-reviews "developer"))
                     app-id))
      (is (not (contains? (get-ids (get-v2-handled-reviews "developer"))
                          app-id))))

    (testing "lists handled in handled reviews"
      (is (= {:success true} (send-dynamic-command "developer" {:type :rems.workflow.dynamic/approve
                                                                :application-id app-id
                                                                :comment ""})))
      (is (not (contains? (get-ids (get-v2-open-reviews "developer"))
                          app-id)))
      (is (contains? (get-ids (get-v2-handled-reviews "developer"))
                     app-id)))))
