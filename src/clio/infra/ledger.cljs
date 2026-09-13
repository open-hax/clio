(ns clio.infra.ledger
  (:require [clio.domain.canonicalize :as canonicalize]
            [clio.domain.schema :as schema]
            [clio.extern.js.fs :as fs]
            [clio.law.ledger :as ledger-law]
            [clio.shape.edn :as edn]
            [clojure.string :as str]))

(defn- fail!
  [type message data]
  (throw (ex-info message (assoc data :clio/error type))))

(defn create-ledger!
  [path]
  (fs/create-exclusive! path))

(defn- parse-ledger-text
  [path text]
  (->> text
       str/split-lines
       (map-indexed vector)
       (remove (fn [[_ line]] (str/blank? line)))
       (mapv
        (fn [[index line]]
          (try
            (edn/read-one line)
            (catch :default cause
              (fail! :clio.ledger/invalid-edn
                     "Ledger line must contain exactly one readable EDN form"
                     {:path path
                      :line (inc index)
                      :cause (str cause)})))))))

(defn read-ledger
  [path]
  (if-not (fs/exists? path)
    []
    (parse-ledger-text path (fs/read-text path))))

(defn- append-record!
  [lock existing-text event]
  (let [delimiter (if (or (str/blank? existing-text)
                          (str/ends-with? existing-text "\n"))
                    ""
                    "\n")]
    (fs/append-locked-text! lock (str delimiter (pr-str event) "\n"))))

(defn- require-ledger-path!
  "A caller naming an explicit partition file means it to exist. Treating a
   misspelled, deleted, or unmounted path as an empty ledger would let
   canonicalization silently produce a plausible but incomplete history, and
   would let an append open a fresh ledger while the intended one stays
   behind. `create-ledger!` is the only path that brings a ledger into being."
  [path]
  (when-not (fs/exists? path)
    (fail! :clio.ledger/missing-file
           "Ledger file does not exist"
           {:path path})))

(defn append-event!
  "Append one validated event while holding an OS-backed exclusive lock on
   the ledger inode. Exact retries are idempotent. Causal parents may live in
   other physical ledger files; complete-history causality is checked when
   ledgers are unioned."
  [revisions path event]
  (require-ledger-path! path)
  (let [lock (fs/acquire-lock! path)]
    (try
      (schema/validate-event! revisions event)
      (let [existing-text (fs/read-locked-text lock)
            events (parse-ledger-text path existing-text)]
        (doseq [existing events]
          (schema/validate-event! revisions existing))
        ;; The admission decision is a law, not transport; this namespace only
        ;; carries its verdict back across the boundary as an error or a write.
        (let [{:admission/keys [verdict conflict]}
              (ledger-law/append-admission events event)]
          (case verdict
            :already-present
            :already-present

            :id-collision
            (fail! :clio.ledger/id-collision
                   "Ledger already contains different data for this event id"
                   {:old conflict :new event})

            :stream-slot-conflict
            (fail! :clio.ledger/concurrent-stream-write
                   "Ledger already contains a different event at this stream revision"
                   {:old conflict :new event})

            :appendable
            (do
              (append-record! lock existing-text event)
              :appended))))
      (finally
        (fs/release-lock! lock)))))

(defn read-ledgers
  [paths]
  (doseq [path paths] (require-ledger-path! path))
  (mapv read-ledger paths))

(defn canonicalize-files
  [revisions paths]
  (canonicalize/canonicalize revisions (read-ledgers paths)))
