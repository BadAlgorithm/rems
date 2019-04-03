(ns rems.text
  #?(:clj (:require [clj-time.core :as time]
                    [clj-time.format :as format]
                    [rems.context :as context]
                    [rems.locales :as locales]
                    [taoensso.tempura :refer [tr]])
     :cljs (:require [cljs-time.core :as time]
                     [cljs-time.format :as format]
                     [re-frame.core :as rf]
                     [taoensso.tempura :refer [tr]])))

(defn with-language [lang f]
  #?(:clj (binding [context/*lang* lang
                    context/*tempura* (partial tr @locales/tempura-config [lang])]
            (f))))

(defn text-format
  "Return the tempura translation for a given key & format arguments"
  [k & args]
  #?(:clj (context/*tempura* [k :t/missing] (vec args))
     :cljs (let [translations (rf/subscribe [:translations])
                 language (rf/subscribe [:language])]
             (tr {:dict @translations}
                 [@language]
                 [k :t/missing]
                 (vec args)))))

(defn text
  "Return the tempura translation for a given key. Additional fallback
  keys can be given."
  [& ks]
  #?(:clj (context/*tempura* (conj (vec ks) (text-format :t/missing (vec ks))))
     :cljs (let [translations (rf/subscribe [:translations])
                 language (rf/subscribe [:language])]
             (try
               (tr {:dict @translations}
                   [@language]
                   (conj (vec ks) (text-format :t/missing (vec ks))))
               (catch js/Object e
                 ;; fail gracefully if the re-frame state is incomplete
                 (.error js/console e)
                 (str (vec ks)))))))

(defn localized [m]
  (let [lang #?(:clj context/*lang*
                :cljs @(rf/subscribe [:language]))]
    (or (get m lang)
        (get m :default)
        (first (vals m)))))

(def ^:private states
  {:rems.workflow.dynamic/draft :t.applications.dynamic-states/draft
   :rems.workflow.dynamic/submitted :t.applications.dynamic-states/submitted
   :rems.workflow.dynamic/approved :t.applications.dynamic-states/approved
   :rems.workflow.dynamic/rejected :t.applications.dynamic-states/rejected
   :rems.workflow.dynamic/closed :t.applications.dynamic-states/closed
   :rems.workflow.dynamic/returned :t.applications.dynamic-states/returned})

(defn localize-state [state]
  (text (get states state :t.applications.states/unknown)))

(def ^:private event-types
  {:application.event/approved :t.applications.dynamic-events/approved
   :application.event/closed :t.applications.dynamic-events/closed
   :application.event/comment-requested :t.applications.dynamic-events/comment-requested
   :application.event/commented :t.applications.dynamic-events/commented
   :application.event/created :t.applications.dynamic-events/created
   :application.event/decided :t.applications.dynamic-events/decided
   :application.event/decision-requested :t.applications.dynamic-events/decision-requested
   :application.event/draft-saved :t.applications.dynamic-events/draft-saved
   :application.event/member-added :t.applications.dynamic-events/member-added
   :application.event/member-invited :t.applications.dynamic-events/member-invited
   :application.event/member-joined :t.applications.dynamic-events/member-joined
   :application.event/member-removed :t.applications.dynamic-events/member-removed
   :application.event/member-uninvited :t.applications.dynamic-events/member-uninvited
   :application.event/rejected :t.applications.dynamic-events/rejected
   :application.event/returned :t.applications.dynamic-events/returned
   :application.event/submitted :t.applications.dynamic-events/submitted})

(defn localize-event [event-type]
  (text (get event-types event-type :t.applications.events/unknown)))

(defn localize-decision [decision]
  (text (case decision
          :approved :t.applications.dynamic-events/approved
          :rejected :t.applications.dynamic-events/rejected
          :t.applications.events/unknown)))

(def ^:private time-format
  (format/formatter "yyyy-MM-dd HH:mm" (time/default-time-zone)))

(defn localize-time [time]
  #?(:clj (format/unparse time-format time)
     :cljs (let [time (if (string? time) (format/parse time) time)]
             (when time
               (format/unparse-local time-format (time/to-default-time-zone time))))))

(defn localize-item
  ([item]
   #?(:cljs (localize-item item @(rf/subscribe [:language]))))
  ([item language]
   #?(:cljs (merge item (get-in item [:localizations language])))))
