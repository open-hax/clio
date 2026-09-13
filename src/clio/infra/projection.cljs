(ns clio.infra.projection
  (:require [clio.domain.projection :as projection]
            [clio.extern.js.crypto :as crypto]
            [clio.extern.js.fs :as fs]
            [clio.extern.js.runtime :as runtime]
            [clio.infra.ledger :as ledger]
            [clio.shape.canonical :as canonical]))

(defn build
  [canonical-ledger initial apply-event]
  (assoc (projection/projection canonical-ledger initial apply-event)
         :projection/source-hash
         (crypto/sha256
          (canonical/canonical-edn (:canonical/events canonical-ledger)))))

(defn write!
  "Replace a disposable projection atomically. Re-running from the same canonical
   event set and reducer yields the same logical EDN value rather than applying
   mutations to the previous projection."
  [path value]
  (let [tmp (str path ".tmp-" (runtime/random-uuid))]
    (try
      (fs/write-text! tmp (str (pr-str value) "\n"))
      (fs/rename! tmp path)
      value
      (finally
        (fs/delete-if-exists! tmp)))))

(defn project-files!
  [{:keys [revisions ledger-files output-file initial apply-event]}]
  (let [canonical-ledger (ledger/canonicalize-files revisions ledger-files)
        value (build canonical-ledger initial apply-event)]
    (write! output-file value)))
