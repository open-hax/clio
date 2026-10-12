(ns clio.law.append
  "The explicit recovery input preserves a candidate, without certifying admission."
  (:require [malli.core :as m]))

(def recovery
  [:map {:closed true}
   [:ledger/path [:string {:min 1}]]
   [:event :map]])

(defn validate-recovery!
  [value]
  (when-not (m/validate recovery value)
    (throw (ex-info "Append recovery requires a ledger path and the exact event"
                    {:clio/error :clio.runtime/invalid-append-recovery
                     :explain (m/explain recovery value)})))
  value)
