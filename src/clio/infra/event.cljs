(ns clio.infra.event
  (:require [clio.domain.schema :as schema]
            [clio.extern.js.runtime :as runtime]))

(defn make-event
  "Create a new event under the current content-derived schema revision. Callers
   provide only domain/stream facts; event id, type, schema identity, and wall
   clock metadata are stamped here."
  [revision schema-id event]
  (let [event
        (merge {:event/causes []}
               event
               {:event/id (runtime/random-uuid)
                :event/type schema-id
                :event/schema (schema/schema-ref revision schema-id)
                :event/at (runtime/now-iso)})]
    (schema/validate-event! [revision] event)
    event))
