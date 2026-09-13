(ns clio.law.ledger-test
  (:require [clio.law.ledger :as ledger-law]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])))

(defn- event
  [id stream seq-no data]
  {:event/id id
   :event/type :counter/added
   :event/stream stream
   :event/seq seq-no
   :event/causes []
   :event/actor "user:alice"
   :event/subject stream
   :event/at "2026-08-09T00:00:00.000Z"
   :event/data data})

(def ^:private a (event "11111111-1111-4111-8111-111111111111" "counter:a" 1 {:amount 1}))
(def ^:private b (event "22222222-2222-4222-8222-222222222222" "counter:a" 2 {:amount 2}))

(defn- verdict
  [events candidate]
  (:admission/verdict (ledger-law/append-admission events candidate)))

(deftest an-unclaimed-id-and-slot-is-appendable
  (is (= :appendable (verdict [] a)))
  (is (= :appendable (verdict [a] b))))

(deftest an-exact-retry-is-already-present
  ;; Law 2: exact duplicate ids dedupe, so a retry is a no-op rather than a
  ;; collision with itself — identity is checked before the slot for this
  ;; reason, since a retry also claims its own [stream seq].
  (is (= :already-present (verdict [a] a)))
  (is (= :already-present (verdict [a b] a))))

(deftest the-same-id-carrying-different-data-is-corruption
  ;; Law 2: :event/id is globally stable identity.
  (let [tampered (assoc-in a [:event/data :amount] 99)
        result (ledger-law/append-admission [a] tampered)]
    (is (= :id-collision (:admission/verdict result)))
    (testing "and the offending event is named"
      (is (= a (:admission/conflict result))))))

(deftest a-contested-stream-slot-is-a-concurrent-write
  ;; Law 4: [stream seq] is an order-sensitive slot; two distinct events
  ;; claiming one is a conflict.
  (let [rival (event "33333333-3333-4333-8333-333333333333" "counter:a" 1 {:amount 7})
        result (ledger-law/append-admission [a] rival)]
    (is (= :stream-slot-conflict (:admission/verdict result)))
    (testing "and the incumbent is named"
      (is (= a (:admission/conflict result))))))

(deftest the-same-slot-number-in-another-stream-is-not-a-conflict
  (let [other (event "44444444-4444-4444-8444-444444444444" "counter:b" 1 {:amount 5})]
    (is (= :appendable (verdict [a] other)))))

(deftest a-verdict-that-admits-carries-no-conflict
  (is (nil? (:admission/conflict (ledger-law/append-admission [] a))))
  (is (nil? (:admission/conflict (ledger-law/append-admission [a] a)))))
