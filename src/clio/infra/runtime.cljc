(ns clio.infra.runtime
  (:require [clio.domain.canonicalize :as canonicalize]
            [clio.domain.schema :as schema]
            #?(:clj [clio.extern.jvm.crypto :as crypto]
               :cljs [clio.extern.js.crypto :as crypto])
            #?(:clj [clio.extern.jvm.fs :as fs]
               :cljs [clio.extern.js.fs :as fs])
            [clio.infra.event :as event]
            [clio.infra.ledger :as ledger]
            [clio.infra.schema-store :as schema-store]
            [clio.law.append :as append-law]))

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

(defn canonicalize-files
  "Capture complete locked snapshots before loading their published schema revisions.
   Schema files are immutable and precede the events that reference them; refreshing
   after capture avoids mixing newer events with an earlier revision inventory."
  [runtime paths]
  (let [snapshots (ledger/read-ledgers paths)
        revisions (:schema/revisions (refresh runtime))]
    (canonicalize/canonicalize revisions snapshots)))

(defn ensure-durable!
  "Refresh schema revisions under the existing ledger's durability lock."
  [runtime path]
  (ledger/ensure-durable-with! #(:schema/revisions (refresh runtime)) path))

(defn retry-append!
  "Explicitly retry a preserved candidate without generating identity or time.
   Exact visible events still require validated inode and parent durability."
  [runtime recovery]
  (append-law/validate-recovery! recovery)
  (try
    (let [runtime (refresh runtime)
          {:ledger/keys [path] :keys [event]} recovery]
      {:append/result (ledger/append-event! (:schema/revisions runtime) path event)
       :event event
       :runtime runtime})
    (catch #?(:clj Exception :cljs :default) cause
      (throw (ex-info (ex-message cause)
                      (assoc (ex-data cause) :clio/append-recovery recovery)
                      cause)))))

(defn- recovery-path [path]
  (try (fs/absolute-path path)
       (catch #?(:clj Exception :cljs :default) cause
         (if (fs/missing-path-error? cause)
           (throw (ex-info "Ledger file does not exist"
                           {:clio/error :clio.ledger/missing-file :path path} cause))
           (throw cause)))))

(defn append!
  "Create and append an event. Admission/persistence failures expose the exact
   candidate in exception data under :clio/append-recovery for explicit retry."
  [runtime ledger-file schema-id event-data]
  (retry-append! runtime
                 {:ledger/path (recovery-path ledger-file)
                  :event (event/make-event (:schema/current runtime) schema-id event-data)}))
