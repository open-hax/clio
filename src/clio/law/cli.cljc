(ns clio.law.cli
  (:require [malli.core :as m]))

(def command-args
  {:new [:tuple :string]
   :schema-root [:tuple :string]
   :append [:tuple :string :string :string :string :string]
   :canonicalize
   [:and
    [:vector :string]
    [:fn {:error/message "requires schema directory and at least one ledger"}
     #(>= (count %) 2)]]})

(def catalog [:map-of :keyword :any])
(def schema-id :keyword)
(def event-data :map)

(defn validate!
  [schema value error-data]
  (when-not (m/validate schema value)
    (throw
     (ex-info "CLI input violates its contract"
              (merge {:clio/error :clio.cli/invalid-input
                      :explain (m/explain schema value)}
                     error-data))))
  value)

(defn validate-command-args!
  [command args]
  (if-let [schema (get command-args command)]
    (validate! schema (vec args) {:command command})
    (throw
     (ex-info "Unknown Clio command"
              {:clio/error :clio.cli/unknown-command
               :command command}))))
