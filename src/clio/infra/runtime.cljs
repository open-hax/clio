(ns clio.infra.runtime
  (:require [clio.domain.schema :as schema]
            [clio.extern.js.crypto :as crypto]
            [clio.infra.event :as event]
            [clio.infra.ledger :as ledger]
            [clio.infra.schema-store :as schema-store]))

(defn open
  "Materialize the current source catalog, persist it if it is new, and load all
   historical catalog snapshots. Editing schema data is enough to create a new
   version; there is no version constant to remember."
  [schema-directory catalog]
  (let [current (schema/materialize crypto/sha256 catalog)]
    (schema-store/ensure-revision! schema-directory current)
    {:schema/directory schema-directory
     :schema/current current
     :schema/revisions (schema-store/load-revisions schema-directory)}))

(defn refresh
  [runtime]
  (assoc runtime
         :schema/revisions
         (schema-store/load-revisions (:schema/directory runtime))))

(defn append!
  [runtime ledger-file schema-id event-data]
  (let [runtime (refresh runtime)
        event (event/make-event (:schema/current runtime) schema-id event-data)]
    {:append/result
     (ledger/append-event! (:schema/revisions runtime) ledger-file event)
     :event event
     :runtime runtime}))
