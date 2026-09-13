(ns clio.law.schema
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(def uuid-string-schema
  [:string {:min 36 :max 36}])

(def hash-schema
  [:string {:min 64 :max 64}])

(def schema-ref-schema
  [:map {:closed true}
   [:schema/root hash-schema]
   [:schema/id :keyword]
   [:schema/hash hash-schema]])

(def bootstrap-schema
  "Fixed protocol foothold used only to discover the historical event schema.
   It is deliberately not an addressable event schema."
  [:map
   [:event/id uuid-string-schema]
   [:event/schema schema-ref-schema]
   [:event/type :keyword]])

(defn event-schema
  "Describe a complete event contract for event-type. The complete envelope is
   part of each catalog leaf, so envelope changes change schema identity."
  [event-type data-schema]
  [:map {:closed true}
   [:event/id uuid-string-schema]
   [:event/schema schema-ref-schema]
   [:event/type [:= event-type]]
   [:event/stream [:string {:min 1}]]
   [:event/seq [:int {:min 1}]]
   [:event/causes [:vector uuid-string-schema]]
   [:event/actor [:string {:min 1}]]
   [:event/subject [:string {:min 1}]]
   [:event/at [:string {:min 1}]]
   [:event/data data-schema]])

(defn merge-catalogs
  [& catalogs]
  (reduce
   (fn [acc catalog]
     (reduce-kv
      (fn [acc schema-id schema-form]
        (if-let [old (get acc schema-id)]
          (if (= old schema-form)
            acc
            (throw
             (ex-info "Conflicting definitions for schema id"
                      {:clio/error :clio.schema/conflicting-definition
                       :schema/id schema-id
                       :first old
                       :second schema-form})))
          (assoc acc schema-id schema-form)))
      acc
      catalog))
   {}
   catalogs))

(defn validate-bootstrap!
  [event]
  (when-not (m/validate bootstrap-schema event)
    (throw
     (ex-info "Event does not contain a readable schema bootstrap"
              {:clio/error :clio.schema/invalid-bootstrap
               :explain (m/explain bootstrap-schema event)
               :event event})))
  event)

(defn validate-event-form!
  "Validate a complete event against a historical catalog. The catalog is
   supplied as Malli's registry, so qualified schema references inside an event
   contract resolve against the same revision that supplied the event schema."
  [catalog schema-id event]
  (let [schema-form (get catalog schema-id)
        registry (mr/composite-registry m/default-registry catalog)
        compiled (m/schema schema-form {:registry registry})]
    (when-not (m/validate compiled event)
      (throw
       (ex-info "Event does not match its historical Malli schema"
                {:clio/error :clio.schema/invalid-event
                 :schema/id schema-id
                 :explain (m/explain compiled event)
                 :event event}))))
  event)
