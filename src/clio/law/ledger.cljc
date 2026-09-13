(ns clio.law.ledger
  "Whether a candidate event may join a ledger partition, and why not when it
   may not.

   This is a restatement of two published laws, not filesystem bookkeeping:

     Law 2 — `:event/id` is globally stable identity. Exact duplicate ids
             dedupe; the same id carrying different data is corruption.
     Law 4 — `[:event/stream :event/seq]` is an order-sensitive slot. Two
             distinct events claiming one slot are a concurrent-write
             conflict.

   Keeping the decision here rather than beside the file descriptor means a
   second ledger transport inherits the contract instead of reimplementing
   it.")

(defn- same-id
  [events event]
  (some #(when (= (:event/id %) (:event/id event)) %) events))

(defn- same-slot
  [events event]
  (some #(when (and (= (:event/stream %) (:event/stream event))
                    (= (:event/seq %) (:event/seq event)))
           %)
        events))

(defn append-admission
  "Classify `event` against the events already in one partition.

   Returns `{:admission/verdict v}`, plus `:admission/conflict` naming the
   event responsible whenever the verdict is a refusal:

     :appendable           — no id and no slot claim collides.
     :already-present      — byte-identical retry; appending again is a no-op.
     :id-collision         — same `:event/id`, different data (Law 2).
     :stream-slot-conflict — same `[stream seq]`, different event (Law 4).

   Order matters: identity is checked before the slot, so an exact retry stays
   idempotent rather than reading as a conflict with itself."
  [events event]
  (let [by-id (same-id events event)
        by-slot (same-slot events event)]
    (cond
      (= by-id event) {:admission/verdict :already-present}
      by-id {:admission/verdict :id-collision
             :admission/conflict by-id}
      by-slot {:admission/verdict :stream-slot-conflict
               :admission/conflict by-slot}
      :else {:admission/verdict :appendable})))
