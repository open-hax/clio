(ns clio.infra.node-durability-test
  "Node schema publication must reach stable storage before a dependent ledger append."
  (:require [cljs.test :refer [deftest is]]
            [clio.extern.js.fs :as fs]
            [clio.extern.js.fs-observer :as observer]
            [clio.extern.js.runtime :as host]
            [clio.infra.host-fixture :as fixture]
            [clio.infra.ledger :as ledger]
            [clio.infra.runtime :as runtime]
            [clojure.string :as str]))

(defn- with-directory [operation]
  (let [directory (str "/tmp/clio-durability-" (host/random-uuid))]
    (try (fs/ensure-dir! directory) (operation directory)
         (finally (fs/remove-tree! directory)))))

(defn- indexes [events predicate]
  (keep-indexed #(when (predicate %2) %1) events))

(defn- error-code [operation]
  (try (operation) nil (catch :default cause (:clio/error (ex-data cause)))))

(deftest schema-content-and-directory-entry-are-synced-before-event-acknowledgement
  (with-directory
    (fn [directory]
      (let [trace (atom []) path (str directory "/events.edn") schemas (str directory "/schemas")]
        (observer/with-observer #(swap! trace conj %)
          #(do (ledger/create-ledger! path)
               (runtime/append! (runtime/open schemas fixture/catalog) path :record/observed fixture/facts)))
        (let [temp-sync (first (indexes @trace #(and (= :sync (:operation %))
                                                    (str/includes? (str (:path %)) ".tmp-"))))
              rename-index (first (indexes @trace #(= :rename (:operation %))))
              ledger-syncs (indexes @trace #(and (= :sync (:operation %)) (= path (:path %))))
              dir-after (filter #(and rename-index (> % rename-index))
                                (indexes @trace #(and (= :sync (:operation %)) (= schemas (:path %)) (:directory? %))))]
          (is (number? temp-sync) "The schema temporary inode was fsynced")
          (is (and temp-sync rename-index (< temp-sync rename-index)))
          (is (seq dir-after) "The published schema directory entry was fsynced after rename")
          (is (and (seq dir-after) (seq ledger-syncs) (< (last dir-after) (last ledger-syncs))))
          (is (>= (count ledger-syncs) 2) "Both empty ledger initialization and admitted append were synced"))))))

(deftest schema-sync-failure-prevents-dependent-event-admission
  (with-directory
    (fn [directory]
      (let [path (str directory "/events.edn") schemas (str directory "/schemas")
            failure (ex-info "Injected schema fsync refusal" {:injected true})]
        (ledger/create-ledger! path)
        (let [rejected (try
                         (observer/with-observer
                           #(when (and (= :sync (:operation %)) (str/includes? (str (:path %)) ".tmp-"))
                              (throw failure))
                           #(runtime/append! (runtime/open schemas fixture/catalog) path :record/observed fixture/facts))
                         false (catch :default _ true))]
          (is rejected "A schema fsync error must reach the caller")
          (is (= [] (ledger/read-ledger path)) "No event refers to the refused schema"))))))

(deftest directory-sync-refusal-precedes-schema-rename
  (with-directory
    (fn [directory]
      (let [from (str directory "/pending.edn") to (str directory "/committed.edn")]
        (fs/write-text! from "{:schema :new}")
        (is (= :clio.fs/directory-sync-unavailable
               (error-code
                 #(observer/with-observer
                    (fn [event] (when (:directory? event) (throw (ex-info "Unsupported directory force" {}))))
                    (fn [] (fs/rename! from to))))))
        (is (fs/exists? from))
        (is (not (fs/exists? to)))))))

(deftest post-rename-directory-refusal-blocks-append-and-retry-resynchronizes
  (with-directory
    (fn [directory]
      (let [path (str directory "/events.edn") schemas (str directory "/schemas")
            renamed? (atom false)]
        (ledger/create-ledger! path)
        (is (= :clio.fs/directory-sync-unavailable
               (error-code
                 #(observer/with-observer
                    (fn [event]
                      (when (= :rename (:operation event)) (reset! renamed? true))
                      (when (and @renamed? (:directory? event))
                        (throw (ex-info "Injected publication directory force failure" {}))))
                    (fn [] (runtime/append! (runtime/open schemas fixture/catalog)
                                           path :record/observed fixture/facts))))))
        (is @renamed? "The durable file moved, but publication was not acknowledged")
        (is (= [] (ledger/read-ledger path)))
        (let [trace (atom [])]
          (observer/with-observer #(swap! trace conj %)
            #(runtime/append! (runtime/open schemas fixture/catalog) path :record/observed fixture/facts))
          (is (some #(and (:directory? %) (= schemas (:path %))) @trace)
              "Opening an existing snapshot retries directory durability before admission")
          (is (= 1 (count (ledger/read-ledger path)))))))))

(deftest failed-new-directory-ancestry-is-resynchronized-on-retry
  (with-directory
    (fn [directory]
      (let [middle (str directory "/new") leaf (str middle "/schemas")]
        (is (= :clio.fs/directory-sync-unavailable
               (error-code
                 #(observer/with-observer
                    (fn [event] (when (= middle (:path event)) (throw (ex-info "Injected ancestor force failure" {}))))
                    (fn [] (fs/ensure-dir! leaf))))))
        (is (fs/exists? leaf))
        (let [trace (atom [])]
          (observer/with-observer #(swap! trace conj %) #(fs/ensure-dir! leaf))
          (is (every? (set (map :path @trace)) [leaf middle directory "/tmp" "/"]))
          (is (every? :directory? @trace)))))))
