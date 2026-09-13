(ns clio.infra.cli
  (:require [clio.domain.schema :as schema]
            [clio.extern.js.crypto :as crypto]
            [clio.extern.js.fs :as fs]
            [clio.infra.ledger :as ledger]
            [clio.infra.runtime :as runtime]
            [clio.infra.schema-store :as schema-store]
            [clio.law.cli :as cli-law]
            [clio.shape.edn :as edn]))

(defn- usage []
  (println "clio new <ledger.edn>")
  (println "clio schema-root <catalog.edn>")
  (println "clio append <schema-dir> <catalog.edn> <ledger.edn> <:schema/id> '<event-edn>'")
  (println "clio canonicalize <schema-dir> <ledger.edn> [ledger.edn ...]"))

(defn- read-edn-file [path]
  (edn/read-one (fs/read-text path)))

(defn- command-new [[path]]
  (ledger/create-ledger! path)
  (prn {:ledger/path path :ledger/created? true}))

(defn- command-schema-root [[catalog-file]]
  (let [catalog-value (read-edn-file catalog-file)
        catalog (cli-law/validate! cli-law/catalog catalog-value {:input :catalog})
        revision (schema/materialize crypto/sha256 catalog)]
    (prn {:schema/root (:schema/root revision)
          :schema/hashes (:schema/hashes revision)})))

(defn- command-append [[schema-dir catalog-file ledger-file schema-id-edn event-edn]]
  (let [catalog-value (read-edn-file catalog-file)
        schema-id-value (edn/read-one schema-id-edn)
        event-data-value (edn/read-one event-edn)
        catalog (cli-law/validate! cli-law/catalog catalog-value {:input :catalog})
        schema-id (cli-law/validate! cli-law/schema-id schema-id-value {:input :schema-id})
        event-data (cli-law/validate! cli-law/event-data event-data-value {:input :event-data})
        rt (runtime/open schema-dir catalog)
        result (runtime/append! rt ledger-file schema-id event-data)]
    (prn (select-keys result [:append/result :event]))))

(defn- command-canonicalize [[schema-dir & ledger-files]]
  (let [revisions (schema-store/load-revisions schema-dir)
        canonical (ledger/canonicalize-files revisions ledger-files)]
    (prn (select-keys canonical [:canonical/event-ids :canonical/events]))))

(defn -main [& args]
  (let [[command-name & command-args] args
        command (some-> command-name keyword)]
    (when-not command
      (usage)
      (throw
       (ex-info "Missing Clio command"
                {:clio/error :clio.cli/missing-command})))
    (cli-law/validate-command-args! command command-args)
    (case command
      :new (command-new command-args)
      :schema-root (command-schema-root command-args)
      :append (command-append command-args)
      :canonicalize (command-canonicalize command-args))))
