(ns clio.domain.canonicalize
  (:require [clio.domain.schema :as schema]))

(defn- fail!
  [type message data]
  (throw (ex-info message (assoc data :clio/error type))))

(defn- validate-and-dedupe
  [revisions events]
  (vals
   (reduce
    (fn [by-id event]
      (schema/validate-event! revisions event)
      (let [id (:event/id event)]
        (if-let [old (get by-id id)]
          (if (= old event)
            by-id
            (fail! :clio.canonicalize/id-collision
                   "The same event id has different contents"
                   {:event/id id :first old :second event}))
          (assoc by-id id event))))
    {}
    events)))

(defn- event-by-id
  [events]
  (into {} (map (juxt :event/id identity)) events))

(defn- verify-parents!
  [by-id]
  (doseq [[id event] by-id
          parent (:event/causes event)]
    (when-not (contains? by-id parent)
      (fail! :clio.canonicalize/missing-parent
             "Event references a causal parent absent from the ledger union"
             {:event/id id :missing/event-id parent})))
  by-id)

(defn- verify-stream-slots!
  [events]
  (doseq [[[stream seq] slot-events]
          (group-by (juxt :event/stream :event/seq) events)]
    (when (> (count slot-events) 1)
      (fail! :clio.canonicalize/concurrent-stream-write
             "Multiple distinct events claim the same stream revision"
             {:event/stream stream
              :event/seq seq
              :event/ids (mapv :event/id slot-events)})))
  events)

(defn- verify-stream-history!
  [events]
  (doseq [[stream stream-events] (group-by :event/stream events)]
    (let [ordered (sort-by :event/seq stream-events)]
      (doseq [[expected event] (map vector (iterate inc 1) ordered)]
        (when-not (= expected (:event/seq event))
          (fail! :clio.canonicalize/stream-gap
                 "Stream revisions must be contiguous from one"
                 {:event/stream stream
                  :expected/seq expected
                  :actual/seq (:event/seq event)
                  :event/id (:event/id event)})))
      (doseq [[previous event] (partition 2 1 ordered)]
        (when-not (some #{(:event/id previous)} (:event/causes event))
          (fail! :clio.canonicalize/stream-predecessor-not-causal
                 "Each stream revision must directly name its previous revision as a cause"
                 {:event/stream stream
                  :previous/event-id (:event/id previous)
                  :event/id (:event/id event)})))))
  events)

(defn- graph
  [by-id]
  (let [parents (into {}
                      (map (fn [[id event]] [id (set (:event/causes event))]))
                      by-id)
        children
        (reduce-kv
         (fn [acc id parent-ids]
           (reduce (fn [acc parent-id]
                     (update acc parent-id (fnil conj #{}) id))
                   acc
                   parent-ids))
         (zipmap (keys by-id) (repeat #{}))
         parents)]
    {:parents parents
     :children children}))

(defn- tie-key
  [by-id id]
  (let [event (get by-id id)]
    [(:event/stream event) (:event/seq event) id]))

(defn- topo-order
  "Kahn's algorithm with a `ready` set ordered by `tie-key` so the next event
   to emit is always the current minimum. Sorting the whole shrinking ready
   set on every pop (as a plain set + `sort-by` would) is O(n log n) per
   event; keeping `ready` as a sorted-set of tie-keys makes both the min
   lookup and each insert/remove O(log n), so a history with many
   graph-incomparable events (e.g. thousands of independent single-event
   streams) stays O(n log n) overall instead of O(n^2 log n)."
  [by-id {:keys [parents children]}]
  (let [indegree (into {} (map (fn [[id parent-ids]] [id (count parent-ids)])) parents)
        ready (into (sorted-set)
                    (keep (fn [[id degree]] (when (zero? degree) (tie-key by-id id))))
                    indegree)]
    (loop [indegree indegree
           ready ready
           order []]
      (if (empty? ready)
        (if (= (count order) (count by-id))
          order
          (fail! :clio.canonicalize/causal-cycle
                 "Causal graph contains a cycle"
                 {:remaining/event-ids
                  (->> indegree
                       (keep (fn [[id degree]] (when (pos? degree) id)))
                       vec)}))
        (let [key (first ready)
              id (peek key)
              child-ids (get children id #{})
              [next-indegree next-ready]
              (reduce
               (fn [[degrees candidates] child-id]
                 (let [degree (dec (get degrees child-id))
                       degrees (assoc degrees child-id degree)]
                   [degrees
                    (cond-> candidates
                      (zero? degree) (conj (tie-key by-id child-id)))]))
               [indegree (disj ready key)]
               child-ids)]
          (recur next-indegree next-ready (conj order id)))))))

(defn canonicalize
  "Union arbitrarily partitioned ledgers into one validated causal history.

   Physical ledger order is ignored. Exact UUID duplicates collapse. Conflicting
   UUIDs, absent causal parents, competing stream revisions, stream gaps, broken
   predecessor causality, and graph cycles are refused. Concurrent events in
   different streams remain unordered by the graph; a stable tie-break is used
   only to produce deterministic replay material."
  [revisions ledgers]
  (let [events (validate-and-dedupe revisions (mapcat identity ledgers))
        by-id (-> events
                  verify-stream-slots!
                  verify-stream-history!
                  event-by-id
                  verify-parents!)
        dag (graph by-id)
        order (topo-order by-id dag)]
    {:canonical/events (mapv by-id order)
     :canonical/event-ids order
     :canonical/by-id by-id
     :canonical/graph dag}))
