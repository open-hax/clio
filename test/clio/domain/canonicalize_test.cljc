(ns clio.domain.canonicalize-test
  (:require [clio.domain.canonicalize :as canonicalize]
            [clio.domain.projection :as projection]
            [clio.domain.schema :as schema]
            [clio.law.schema :as schema-law]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])))

(defn fake-hash
  [text]
  (let [token (str (hash text) ":")]
    (apply str (take 64 (cycle token)))))

(def catalog
  {:counter/opened
   (schema-law/event-schema
    :counter/opened
    [:map {:closed true} [:amount :int]])
   :counter/added
   (schema-law/event-schema
    :counter/added
    [:map {:closed true} [:amount :int]])})

(def revision
  (schema/materialize fake-hash catalog))

(defn event
  [id event-type stream seq causes amount]
  {:event/id id
   :event/schema (schema/schema-ref revision event-type)
   :event/type event-type
   :event/stream stream
   :event/seq seq
   :event/causes causes
   :event/actor "user:alice"
   :event/subject stream
   :event/at "2026-08-09T00:00:00.000Z"
   :event/data {:amount amount}})

(def e1
  (event "11111111-1111-4111-8111-111111111111"
         :counter/opened "counter:a" 1 [] 1))

(def e2
  (event "22222222-2222-4222-8222-222222222222"
         :counter/added "counter:a" 2 [(:event/id e1)] 2))

(def e3
  (event "33333333-3333-4333-8333-333333333333"
         :counter/opened "counter:b" 1 [(:event/id e1)] 10))

(def e4
  (event "44444444-4444-4444-8444-444444444444"
         :counter/added "counter:a" 3 [(:event/id e2) (:event/id e3)] 2))

(defn apply-counter
  [state event]
  (case (:event/type event)
    :counter/opened
    (assoc state (:event/stream event) (get-in event [:event/data :amount]))

    :counter/added
    (update state (:event/stream event) + (get-in event [:event/data :amount]))

    state))

(defn error-code
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) cause
      (:clio/error (ex-data cause)))))

(defn without-index
  [values index]
  (into (subvec values 0 index)
        (subvec values (inc index))))

(defn permutations
  [values]
  (if (empty? values)
    [[]]
    (mapcat
     (fn [index]
       (let [head (nth values index)]
         (map #(into [head] %)
              (permutations (without-index values index)))))
     (range (count values)))))

(defn assignments
  [item-count ledger-count]
  (if (zero? item-count)
    [[]]
    (for [prefix (assignments (dec item-count) ledger-count)
          ledger-index (range ledger-count)]
      (conj prefix ledger-index))))

(defn distribute
  [ordered-events assignment ledger-count]
  (reduce
   (fn [ledgers [event ledger-index]]
     (update ledgers ledger-index conj event))
   (vec (repeat ledger-count []))
   (map vector ordered-events assignment)))

(deftest projection-is-invariant-under-physical-partitioning
  (let [events [e1 e2 e3 e4]
        expected (canonicalize/canonicalize [revision] [events])
        expected-ids (:canonical/event-ids expected)
        expected-state (projection/state expected {} apply-counter)
        layouts
        (for [ordered (permutations events)
              assignment (assignments (count events) 3)]
          (distribute ordered assignment 3))]
    (testing "every ordering and assignment of the fixture across three ledgers converges"
      (is
       (every?
        (fn [layout]
          (let [canonical (canonicalize/canonicalize [revision] layout)]
            (and (= expected-ids (:canonical/event-ids canonical))
                 (= expected-state
                    (projection/state canonical {} apply-counter)))))
        layouts)))
    (testing "exact duplicates across physical ledgers also collapse"
      (let [canonical
            (canonicalize/canonicalize
             [revision]
             [[e4 e2 e2] [e3] [e1 e3]])]
        (is (= expected-ids (:canonical/event-ids canonical)))
        (is (= expected-state
               (projection/state canonical {} apply-counter)))))
    (is (= {"counter:a" 5 "counter:b" 10} expected-state))))

(deftest missing-causal-parent-is-refused
  (let [orphan
        (assoc e3 :event/causes
               ["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"])]
    (is (= :clio.canonicalize/missing-parent
           (error-code #(canonicalize/canonicalize [revision] [[e1 orphan]]))))))

(deftest competing-stream-revision-is-refused
  (let [competitor
        (event "55555555-5555-4555-8555-555555555555"
               :counter/added "counter:a" 2 [(:event/id e1)] 99)]
    (is (= :clio.canonicalize/concurrent-stream-write
           (error-code
            #(canonicalize/canonicalize [revision] [[e1 e2 competitor]]))))))

(deftest stream-gap-is-refused
  (let [gap
        (event "88888888-8888-4888-8888-888888888888"
               :counter/added "counter:a" 3 [(:event/id e1)] 3)]
    (is (= :clio.canonicalize/stream-gap
           (error-code #(canonicalize/canonicalize [revision] [[e1 gap]]))))))

(deftest stream-predecessor-must-be-causal
  (let [unrelated
        (event "99999999-9999-4999-8999-999999999999"
               :counter/opened "counter:z" 1 [] 0)
        broken
        (assoc e2 :event/causes [(:event/id unrelated)])]
    (is (= :clio.canonicalize/stream-predecessor-not-causal
           (error-code
            #(canonicalize/canonicalize [revision] [[e1 unrelated broken]]))))))

(deftest uuid-collision-is-refused
  (let [impostor (assoc-in e2 [:event/data :amount] 99)]
    (is (= :clio.canonicalize/id-collision
           (error-code #(canonicalize/canonicalize [revision] [[e1 e2 impostor]]))))))

(deftest causal-cycle-is-refused
  (let [a (event "66666666-6666-4666-8666-666666666666"
                 :counter/opened "counter:x" 1
                 ["77777777-7777-4777-8777-777777777777"] 1)
        b (event "77777777-7777-4777-8777-777777777777"
                 :counter/opened "counter:y" 1
                 ["66666666-6666-4666-8666-666666666666"] 1)]
    (is (= :clio.canonicalize/causal-cycle
           (error-code #(canonicalize/canonicalize [revision] [[a b]]))))))
