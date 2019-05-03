(ns rems.application.model
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [medley.core :refer [map-vals]]
            [rems.application.events :as events]
            [rems.permissions :as permissions]))

;;;; Roles & Permissions

(defn see-application? [application user-id]
  (not= #{:everyone-else} (permissions/user-roles application user-id)))

(defmulti calculate-permissions
  (fn [_application event] (:event/type event)))

(defmethod calculate-permissions :default
  [application _event]
  application)

(def ^:private draft-permissions {:applicant [:application.command/save-draft
                                              :application.command/submit
                                              :application.command/close
                                              :application.command/remove-member
                                              :application.command/invite-member
                                              :application.command/uninvite-member
                                              :application.command/accept-licenses
                                              :application.command/change-resources]
                                  :member [:application.command/accept-licenses]
                                  :handler [:see-everything
                                            :application.command/remove-member
                                            :application.command/uninvite-member]
                                  :commenter [:see-everything]
                                  :decider [:see-everything]
                                  ;; roles whose permissions don't change
                                  :reporter [:see-everything]
                                  :past-commenter [:see-everything]
                                  :past-decider [:see-everything]
                                  ;; member before accepting an invitation
                                  :everyone-else [:application.command/accept-invitation]})

(def ^:private submitted-permissions {:applicant [:application.command/remove-member
                                                  :application.command/uninvite-member
                                                  :application.command/accept-licenses]
                                      :member [:application.command/accept-licenses]
                                      :handler [:see-everything
                                                :application.command/add-licenses
                                                :application.command/add-member
                                                :application.command/change-resources
                                                :application.command/remove-member
                                                :application.command/invite-member
                                                :application.command/uninvite-member
                                                :application.command/request-comment
                                                :application.command/request-decision
                                                :application.command/return
                                                :application.command/approve
                                                :application.command/reject]
                                      :commenter [:see-everything
                                                  :application.command/comment]
                                      :decider [:see-everything
                                                :application.command/decide]})

(def ^:private approved-permissions {:applicant [:application.command/remove-member
                                                 :application.command/uninvite-member
                                                 :application.command/accept-licenses]
                                     :member [:application.command/accept-licenses]
                                     :handler [:see-everything
                                               :application.command/add-member
                                               :application.command/remove-member
                                               :application.command/invite-member
                                               :application.command/uninvite-member
                                               :application.command/close]
                                     :commenter [:see-everything
                                                 :application.command/comment]
                                     :decider [:see-everything
                                               :application.command/decide]})

(def ^:private closed-permissions {:applicant []
                                   :member []
                                   :handler [:see-everything]
                                   :commenter [:see-everything]
                                   :decider [:see-everything]
                                   :everyone-else []})

(defmethod calculate-permissions :application.event/created
  [application event]
  (-> application
      (permissions/give-role-to-users :applicant [(:event/actor event)])
      (permissions/set-role-permissions draft-permissions)))

(defmethod calculate-permissions :application.event/member-added
  [application event]
  (-> application
      (permissions/give-role-to-users :member [(get-in event [:application/member :userid])])))

(defmethod calculate-permissions :application.event/member-joined
  [application event]
  (-> application
      (permissions/give-role-to-users :member [(:event/actor event)])))

(defmethod calculate-permissions :application.event/member-removed
  [application event]
  (-> application
      (permissions/remove-role-from-user :member (get-in event [:application/member :userid]))))

(defmethod calculate-permissions :application.event/submitted
  [application _event]
  (-> application
      (permissions/set-role-permissions submitted-permissions)))

(defmethod calculate-permissions :application.event/returned
  [application _event]
  (-> application
      (permissions/set-role-permissions draft-permissions)))

(defmethod calculate-permissions :application.event/comment-requested
  [application event]
  (-> application
      (permissions/give-role-to-users :commenter (:application/commenters event))))

(defmethod calculate-permissions :application.event/commented
  [application event]
  (-> application
      (permissions/remove-role-from-user :commenter (:event/actor event))
      (permissions/give-role-to-users :past-commenter [(:event/actor event)]))) ; allow to still view the application

(defmethod calculate-permissions :application.event/decision-requested
  [application event]
  (-> application
      (permissions/give-role-to-users :decider (:application/deciders event))))

(defmethod calculate-permissions :application.event/decided
  [application event]
  (-> application
      (permissions/remove-role-from-user :decider (:event/actor event))
      (permissions/give-role-to-users :past-decider [(:event/actor event)]))) ; allow to still view the application

(defmethod calculate-permissions :application.event/approved
  [application _event]
  (-> application
      (permissions/set-role-permissions approved-permissions)))

(defmethod calculate-permissions :application.event/rejected
  [application _event]
  (-> application
      (permissions/set-role-permissions closed-permissions)))

(defmethod calculate-permissions :application.event/closed
  [application _event]
  (-> application
      (permissions/set-role-permissions closed-permissions)))


;;;; Application

(def states
  #{:application.state/approved
    :application.state/closed
    :application.state/draft
    :application.state/rejected
    :application.state/returned
    :application.state/submitted
    #_:application.state/withdrawn})

(defmulti ^:private event-type-specific-application-view
  "See `application-view`"
  (fn [_application event] (:event/type event)))

(defmethod event-type-specific-application-view :application.event/created
  [application event]
  (-> application
      (assoc :application/id (:application/id event)
             :application/external-id (:application/external-id event)
             :application/state :application.state/draft
             :application/created (:event/time event)
             :application/modified (:event/time event)
             :application/applicant (:event/actor event)
             :application/members #{}
             :application/past-members #{}
             :application/invitation-tokens {}
             :application/resources (map #(select-keys % [:catalogue-item/id :resource/ext-id])
                                         (:application/resources event))
             :application/licenses (map #(select-keys % [:license/id])
                                        (:application/licenses event))
             :application/accepted-licenses {}
             :application/events []
             :application/form {:form/id (:form/id event)}
             :application/workflow {:workflow/id (:workflow/id event)
                                    :workflow/type (:workflow/type event)
                                    ;; TODO: other workflows
                                    ;; TODO: extract an event handler for dynamic workflow specific stuff
                                    :workflow.dynamic/awaiting-commenters #{}
                                    :workflow.dynamic/awaiting-deciders #{}})))

(defmethod event-type-specific-application-view :application.event/draft-saved
  [application event]
  (-> application
      (assoc :application/modified (:event/time event))
      (assoc ::draft-answers (:application/field-values event))))

(defmethod event-type-specific-application-view :application.event/licenses-accepted
  [application event]
  (-> application
      (assoc-in [:application/accepted-licenses (:event/actor event)] (:application/accepted-licenses event))))

(defmethod event-type-specific-application-view :application.event/licenses-added
  [application event]
  (-> application
      (assoc :application/modified (:event/time event))
      (update :application/licenses
              (fn [licenses]
                (-> licenses
                    (into (:application/licenses event))
                    distinct
                    vec)))))

(defmethod event-type-specific-application-view :application.event/member-invited
  [application event]
  (-> application
      (update :application/invitation-tokens assoc (:invitation/token event) (:application/member event))))

(defmethod event-type-specific-application-view :application.event/member-uninvited
  [application event]
  (-> application
      (update :application/invitation-tokens (fn [invitations]
                                               (->> invitations
                                                    (remove (fn [[_token member]]
                                                              (= member (:application/member event))))
                                                    (into {}))))))

(defmethod event-type-specific-application-view :application.event/member-joined
  [application event]
  (-> application
      (update :application/members conj {:userid (:event/actor event)})
      (update :application/invitation-tokens dissoc (:invitation/token event))))

(defmethod event-type-specific-application-view :application.event/member-added
  [application event]
  (-> application
      (update :application/members conj (:application/member event))))

(defmethod event-type-specific-application-view :application.event/member-removed
  [application event]
  (-> application
      (update :application/members disj (:application/member event))
      (update :application/past-members conj (:application/member event))))

(defmethod event-type-specific-application-view :application.event/submitted
  [application event]
  (-> application
      (assoc ::previous-submitted-answers (::submitted-answers application))
      (assoc ::submitted-answers (::draft-answers application))
      (dissoc ::draft-answers)
      (assoc :application/state :application.state/submitted)))

(defmethod event-type-specific-application-view :application.event/returned
  [application event]
  (-> application
      (assoc ::draft-answers (::submitted-answers application)) ; guard against re-submit without saving a new draft
      (assoc :application/state :application.state/returned)))

(defmethod event-type-specific-application-view :application.event/comment-requested
  [application event]
  (-> application
      (update-in [:application/workflow :workflow.dynamic/awaiting-commenters] set/union (set (:application/commenters event)))))

(defmethod event-type-specific-application-view :application.event/commented
  [application event]
  (-> application
      (update-in [:application/workflow :workflow.dynamic/awaiting-commenters] disj (:event/actor event))))

(defmethod event-type-specific-application-view :application.event/decision-requested
  [application event]
  (-> application
      (update-in [:application/workflow :workflow.dynamic/awaiting-deciders] set/union (set (:application/deciders event)))))

(defmethod event-type-specific-application-view :application.event/decided
  [application event]
  (-> application
      (update-in [:application/workflow :workflow.dynamic/awaiting-deciders] disj (:event/actor event))))

(defmethod event-type-specific-application-view :application.event/approved
  [application event]
  (-> application
      (assoc :application/state :application.state/approved)))

(defmethod event-type-specific-application-view :application.event/rejected
  [application event]
  (-> application
      (assoc :application/state :application.state/rejected)))

(defmethod event-type-specific-application-view :application.event/resources-changed
  [application event]
  (-> application
      (assoc :application/modified (:event/time event))
      (assoc :application/resources (vec (:application/resources event)))))

(defmethod event-type-specific-application-view :application.event/closed
  [application event]
  (-> application
      (assoc :application/state :application.state/closed)))

(deftest test-event-type-specific-application-view
  (testing "supports all event types"
    (is (= (set (keys events/event-schemas))
           (set (keys (methods event-type-specific-application-view)))))))

(defn- assert-same-application-id [application event]
  (assert (= (:application/id application)
             (:application/id event))
          (str "event for wrong application "
               "(not= " (:application/id application) " " (:application/id event) ")"))
  application)

;; TODO: replace rems.workflow.dynamic/apply-event with this
;;       (it will couple the write and read models, but it's probably okay
;;        because they both are about a single application and are logically coupled)
(defn application-view
  "Projection for the current state of a single application.
  Pure function; must use `enrich-with-injections` to enrich the model with
  data from other entities."
  [application event]
  (-> application
      (event-type-specific-application-view event)
      (calculate-permissions event)
      (assert-same-application-id event)
      (assoc :application/last-activity (:event/time event))
      (update :application/events conj event)))


;;;; Injections

(defn- merge-lists-by
  "Returns a list of merged elements from list1 and list2
   where f returned the same value for both elements."
  [f list1 list2]
  (let [groups (group-by f (concat list1 list2))
        merged-groups (map-vals #(apply merge %) groups)
        merged-in-order (map (fn [item1]
                               (get merged-groups (f item1)))
                             list1)
        list1-keys (set (map f list1))
        orphans-in-order (filter (fn [item2]
                                   (not (contains? list1-keys (f item2))))
                                 list2)]
    (vec (concat merged-in-order orphans-in-order))))

(deftest test-merge-lists-by
  (testing "merges objects with the same key"
    (is (= [{:id 1 :foo "foo1" :bar "bar1"}
            {:id 2 :foo "foo2" :bar "bar2"}]
           (merge-lists-by :id
                           [{:id 1 :foo "foo1"}
                            {:id 2 :foo "foo2"}]
                           [{:id 1 :bar "bar1"}
                            {:id 2 :bar "bar2"}]))))
  (testing "last list overwrites values"
    (is (= [{:id 1 :foo "B"}]
           (merge-lists-by :id
                           [{:id 1 :foo "A"}]
                           [{:id 1 :foo "B"}]))))
  (testing "first list determines the order"
    (is (= [{:id 1} {:id 2}]
           (merge-lists-by :id
                           [{:id 1} {:id 2}]
                           [{:id 2} {:id 1}])))
    (is (= [{:id 2} {:id 1}]
           (merge-lists-by :id
                           [{:id 2} {:id 1}]
                           [{:id 1} {:id 2}]))))
  ;; TODO: or should the unmatched items be discarded? the primary use case is that some fields are removed from a form (unless forms are immutable)
  (testing "unmatching items are added to the end in order"
    (is (= [{:id 1} {:id 2} {:id 3} {:id 4}]
           (merge-lists-by :id
                           [{:id 1} {:id 2}]
                           [{:id 3} {:id 4}])))
    (is (= [{:id 4} {:id 3} {:id 2} {:id 1}]
           (merge-lists-by :id
                           [{:id 4} {:id 3}]
                           [{:id 2} {:id 1}])))))

(defn- localization-for [key item]
  (into {} (for [lang (keys (:localizations item))]
             (when-let [text (get-in item [:localizations lang key])]
               [lang text]))))

(deftest test-localization-for
  (is (= {:en "en title" :fi "fi title"}
         (localization-for :title {:localizations {:en {:title "en title"}
                                                   :fi {:title "fi title"}}})))
  (is (= {:en "en title"}
         (localization-for :title {:localizations {:en {:title "en title"}
                                                   :fi {}}})))
  (is (= {}
         (localization-for :title {:localizations {:en {}
                                                   :fi {}}}))))

(defn- enrich-form [app-form get-form]
  (let [form (get-form (:form/id app-form))
        app-fields (:form/fields app-form)
        rich-fields (map (fn [item]
                           {:field/id (:id item)
                            :field/value "" ; default for new forms
                            :field/type (keyword (:type item))
                            :field/title (localization-for :title item)
                            :field/placeholder (localization-for :inputprompt item)
                            :field/optional (:optional item)
                            :field/options (:options item)
                            :field/max-length (:maxlength item)})
                         (:items form))
        fields (merge-lists-by :field/id rich-fields app-fields)]
    (assoc app-form
           :form/title (:title form)
           :form/fields fields)))

(defn- set-application-description [application]
  (let [fields (get-in application [:application/form :form/fields])
        description (->> fields
                         (filter #(= :description (:field/type %)))
                         first
                         :field/value)]
    (assoc application :application/description (str description))))

(defn- enrich-resources [app-resources get-catalogue-item]
  (->> app-resources
       (map :catalogue-item/id)
       (map get-catalogue-item)
       (map (fn [item]
              {:catalogue-item/id (:id item)
               :resource/id (:resource-id item)
               :resource/ext-id (:resid item)
               :catalogue-item/title (assoc (localization-for :title item)
                                            :default (:title item))
               ;; TODO: remove unused keys
               :catalogue-item/start (:start item)
               :catalogue-item/end (:end item)
               :catalogue-item/enabled (:enabled item)
               :catalogue-item/expired (:expired item)
               :catalogue-item/archived (:archived item)}))
       (sort-by :catalogue-item/id)
       vec))

(defn- enrich-licenses [app-licenses get-license]
  (let [rich-licenses (->> app-licenses
                           (map :license/id)
                           (map get-license)
                           (map (fn [license]
                                  (let [license-type (keyword (:licensetype license))]
                                    (merge {:license/id (:id license)
                                            :license/type license-type
                                            :license/title (assoc (localization-for :title license)
                                                                  :default (:title license))
                                            ;; TODO: remove unused keys
                                            :license/start (:start license)
                                            :license/end (:end license)
                                            :license/enabled (:enabled license)
                                            :license/expired (:expired license)
                                            :license/archived (:archived license)}
                                           (case license-type
                                             :text {:license/text (assoc (localization-for :textcontent license)
                                                                         :default (:textcontent license))}
                                             :link {:license/link (assoc (localization-for :textcontent license)
                                                                         :default (:textcontent license))}
                                             :attachment {:license/attachment-id (assoc (localization-for :attachment-id license)
                                                                                        :default (:attachment-id license))
                                                          ;; TODO: remove filename as unused?
                                                          :license/attachment-filename (assoc (localization-for :textcontent license)
                                                                                              :default (:textcontent license))})))))
                           (sort-by :license/id))]
    (merge-lists-by :license/id rich-licenses app-licenses)))

(defn- enrich-user-attributes [application get-user]
  (letfn [(enrich-members [members]
            (->> members
                 (map (fn [member]
                        (merge member
                               (get-user (:userid member)))))
                 set))]
    (update application
            :application/members
            enrich-members)))

(defn enrich-workflow-handlers [application get-workflow]
  (if (= :workflow/dynamic (get-in application [:application/workflow :workflow/type]))
    (let [workflow (get-workflow (get-in application [:application/workflow :workflow/id]))
          handlers (set (get-in workflow [:workflow :handlers]))]
      (-> application
          (assoc-in [:application/workflow :workflow.dynamic/handlers] handlers)
          (permissions/give-role-to-users :handler handlers)))
    application))

(defn- enrich-super-users [application get-users-with-role]
  (-> application
      (permissions/give-role-to-users :reporter (get-users-with-role :reporter))))

(defn enrich-with-injections [application {:keys [get-form get-catalogue-item get-license
                                                  get-user get-users-with-role get-workflow]}]
  (let [answer-versions (remove nil? [(::draft-answers application)
                                      (::submitted-answers application)
                                      (::previous-submitted-answers application)])
        current-answers (first answer-versions)
        previous-answers (second answer-versions)]
    (-> application
        (dissoc ::draft-answers ::submitted-answers ::previous-submitted-answers)
        (assoc-in [:application/form :form/fields] (merge-lists-by :field/id
                                                                   (map (fn [[field-id value]]
                                                                          {:field/id field-id
                                                                           :field/previous-value value})
                                                                        previous-answers)
                                                                   (map (fn [[field-id value]]
                                                                          {:field/id field-id
                                                                           :field/value value})
                                                                        current-answers)))
        (update :application/form enrich-form get-form)
        set-application-description
        (update :application/resources enrich-resources get-catalogue-item)
        (update :application/licenses enrich-licenses get-license)
        (assoc :application/applicant-attributes (get-user (:application/applicant application)))
        (enrich-user-attributes get-user)
        (enrich-workflow-handlers get-workflow)
        (enrich-super-users get-users-with-role))))

(defn build-application-view [events injections]
  (-> (reduce application-view nil events)
      (enrich-with-injections injections)))


;;;; Authorization

(defmulti ^:private hide-sensitive-event-content
  (fn [event] (:event/type event)))

(defmethod hide-sensitive-event-content :default
  [event]
  event)

(defmethod hide-sensitive-event-content :application.event/member-invited
  [event]
  (dissoc event :invitation/token))

(defmethod hide-sensitive-event-content :application.event/member-joined
  [event]
  (dissoc event :invitation/token))

(defn hide-sensitive-events [events]
  (->> events
       (remove (comp #{:application.event/comment-requested
                       :application.event/commented
                       :application.event/decided
                       :application.event/decision-requested}
                     :event/type))
       (mapv hide-sensitive-event-content)))

(defn- hide-sensitive-information [application]
  (-> application
      (update :application/events hide-sensitive-events)
      (update :application/workflow dissoc :workflow.dynamic/handlers)))

(defn- hide-non-public-information [application]
  (-> application
      ;; the keys are invitation tokens and must be kept secret
      (dissoc :application/invitation-tokens)
      (assoc :application/invited-members (set (vals (:application/invitation-tokens application))))
      ;; these are not used by the UI, so no need to expose them (especially the user IDs)
      (update-in [:application/workflow] dissoc
                 :workflow.dynamic/awaiting-commenters
                 :workflow.dynamic/awaiting-deciders)
      (dissoc :application/past-members)))

(defn apply-user-permissions [application user-id]
  (let [see-application? (see-application? application user-id)
        roles (permissions/user-roles application user-id)
        permissions (permissions/user-permissions application user-id)
        see-everything? (contains? permissions :see-everything)]
    (when see-application?
      (-> (if see-everything?
            application
            (hide-sensitive-information application))
          (hide-non-public-information)
          (assoc :application/permissions permissions)
          (assoc :application/roles roles)
          (permissions/cleanup)))))
