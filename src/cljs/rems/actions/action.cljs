(ns rems.actions.action
  (:require [re-frame.core :as rf]
            [rems.atoms :refer [textarea]]
            [rems.text :refer [text]]))

(defn- action-collapse-id [action-id]
  (str "actions-" action-id))

(defn button-wrapper [{:keys [id text class on-click]}]
  [:button.btn
   {:id id
    :class (or class :btn-secondary)
    :on-click on-click}
   text])

(defn cancel-action-button [id]
  [:button.btn.btn-secondary
   {:id (str "cancel-" id)
    :data-toggle "collapse"
    :data-target (str "#" (action-collapse-id id))}
   (text :t.actions/cancel)])

(defn- action-judge-comment [id label-title comment on-comment]
  (let [id (str "judge-comment-" id)]
    [:div.form-group
     [:label {:for id} label-title]
     [textarea {:id id
                :name id
                :placeholder (text :t.actions/comment-placeholder)
                :value comment
                :on-change on-comment}]]))

(defn action-form-view [id title comment-title buttons content comment on-comment]
  [:div.collapse {:id (action-collapse-id id) :data-parent "#actions-forms"}
   [:h4.mt-5 title]
   content
   (when comment-title
     [action-judge-comment id comment-title comment on-comment])
   (into [:div.col.commands.mr-3 [cancel-action-button id]] buttons)])
