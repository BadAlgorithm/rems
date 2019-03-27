(ns ^:integration rems.api.test-licenses
  (:require [clojure.test :refer :all]
            [rems.handler :refer [handler]]
            [rems.api.testing :refer :all]
            [ring.mock.request :refer :all])
  (:import (java.util UUID)))

(use-fixtures :once api-once-fixture)
(use-fixtures :each api-each-fixture)

(def testfile (clojure.java.io/file "./test-data/test.txt"))

(def filecontent {:tempfile testfile
                  :content-type "text/plain"
                  :filename "test.txt"
                  :size (.length testfile)})

(deftest licenses-api-test
  (let [api-key "42"
        user-id "owner"]
    (testing "get"
      (let [data (-> (request :get "/api/licenses")
                     (authenticate api-key user-id)
                     handler
                     assert-response-is-ok
                     read-body)]
        (is (:id (first data)))))

    (testing "create linked license"
      (let [command {:title (str "license title " (UUID/randomUUID))
                     :licensetype "link"
                     :textcontent "http://example.com/license"
                     :attachment-id nil
                     :localizations {:en {:title "en title"
                                          :textcontent "http://example.com/license/en"
                                          :attachment-id nil}
                                     :fi {:title "fi title"
                                          :textcontent "http://example.com/license/fi"
                                          :attachment-id nil}}}]
        (-> (request :post "/api/licenses/create")
            (authenticate api-key user-id)
            (json-body command)
            handler
            assert-response-is-ok)
        (testing "and fetch"
          (let [body (-> (request :get "/api/licenses")
                         (authenticate api-key user-id)
                         handler
                         assert-response-is-ok
                         read-body)
                license (->> body
                             (filter #(= (:title %) (:title command)))
                             first)]
            (is license)
            (is (= command (select-keys license (keys command))))))))

    (testing "create inline license"
      (let [command {:title (str "license title " (UUID/randomUUID))
                     :licensetype "text"
                     :textcontent "license text"
                     :attachment-id nil
                     :localizations {:en {:title "en title"
                                          :textcontent "en text"
                                          :attachment-id nil}
                                     :fi {:title "fi title"
                                          :textcontent "fi text"
                                          :attachment-id nil}}}]
        (-> (request :post "/api/licenses/create")
            (authenticate api-key user-id)
            (json-body command)
            handler
            assert-response-is-ok)
        (testing "and fetch"
          (let [body (-> (request :get "/api/licenses")
                         (authenticate api-key user-id)
                         handler
                         assert-response-is-ok
                         read-body)
                license (->> body
                             (filter #(= (:title %) (:title command)))
                             first)]
            (is license)
            (is (= command (select-keys license (keys command))))))))

    (testing "Upload an attachment"
      (let [response (-> (request :post (str "/api/licenses/add_attachment"))
                         (assoc :params {"file" filecontent})
                         (assoc :multipart-params {"file" filecontent})
                         (authenticate api-key user-id)
                         handler
                         assert-response-is-ok)
            {:keys [id]} (read-body response)]

        (testing "and test that an id is returned" (is (some? id)))

        (testing "and test that it can be accessed using GET"
          (let [response-file (is (-> (request :get (str "/api/licenses/attachments/" id))
                                      (authenticate api-key user-id)
                                      handler
                                      assert-response-is-ok))]
            (is (= (slurp testfile) (slurp (:body response-file))))))

        (testing "and delete it"
          (-> (request :post (str "/api/licenses/remove_attachment?attachment-id=" id))
              (json-body {:attachment-id id})
              (authenticate api-key user-id)
              handler
              assert-response-is-ok))

        (testing "and check it's not found after deletion"
          (let [response (is (-> (request :get (str "/api/licenses/attachments/" id))
                                 (authenticate api-key user-id)
                                 handler))]
            (is (response-is-not-found? response))))))

    (testing "create attachment license"
      (let [attachment-id (-> (request :post (str "/api/licenses/add_attachment"))
                              (assoc :params {"file" filecontent})
                              (assoc :multipart-params {"file" filecontent})
                              (authenticate api-key user-id)
                              handler
                              assert-response-is-ok
                              read-body
                              (get :id))
            command {:title (str "license title " (UUID/randomUUID))
                     :licensetype "text"
                     :textcontent "license text"
                     :attachment-id attachment-id
                     :localizations {:en {:title "en title"
                                          :textcontent "en text"
                                          :attachment-id attachment-id}
                                     :fi {:title "fi title"
                                          :textcontent "fi text"
                                          :attachment-id attachment-id}}}]
        (-> (request :post "/api/licenses/create")
            (authenticate api-key user-id)
            (json-body command)
            handler
            assert-response-is-ok)

        (testing "and fetch"
          (let [body (-> (request :get "/api/licenses")
                         (authenticate api-key user-id)
                         handler
                         assert-response-is-ok
                         read-body)
                license (->> body
                             (filter #(= (:title %) (:title command)))
                             first)]
            (is license)
            (is (= command (select-keys license (keys command))))))

        (testing "and fail when trying to remove the attachment of the created license"
          (-> (request :post (str "/api/licenses/remove_attachment?attachment-id=" attachment-id))
              (json-body {:attachment-id attachment-id})
              (authenticate api-key user-id)
              handler
              assert-response-is-server-error?))))))

(deftest licenses-api-filtering-test
  (let [unfiltered (-> (request :get "/api/licenses")
                       (authenticate "42" "owner")
                       handler
                       assert-response-is-ok
                       read-body)
        filtered (-> (request :get "/api/licenses" {:active true})
                     (authenticate "42" "owner")
                     handler
                     assert-response-is-ok
                     read-body)]
    (is (coll-is-not-empty? unfiltered))
    (is (coll-is-not-empty? filtered))
    (is (< (count filtered) (count unfiltered)))))

(deftest licenses-api-security-test
  (testing "without authentication"
    (testing "list"
      (let [response (-> (request :get "/api/licenses")
                         handler)]
        (is (response-is-unauthorized? response))
        (is (= "unauthorized" (read-body response)))))
    (testing "create"
      (let [response (-> (request :post "/api/licenses/create")
                         (json-body {:licensetype "text"
                                     :title "t"
                                     :textcontent "t"
                                     :localizations {:en {:title "t"
                                                          :textcontent "t"}}})
                         handler)]
        (is (response-is-unauthorized? response))
        (is (= "Invalid anti-forgery token" (read-body response))))))

  (testing "without owner role"
    (testing "list"
      (let [response (-> (request :get "/api/licenses")
                         (authenticate "42" "alice")
                         handler)]
        (is (response-is-forbidden? response))
        (is (= "forbidden" (read-body response)))))
    (testing "create"
      (let [response (-> (request :post "/api/licenses/create")
                         (authenticate "42" "alice")
                         (json-body {:licensetype "text"
                                     :title "t"
                                     :textcontent "t"
                                     :localizations {:en {:title "t"
                                                          :textcontent "t"}}})
                         handler)]
        (is (response-is-forbidden? response))
        (is (= "forbidden" (read-body response)))))))
