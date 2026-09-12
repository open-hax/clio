(ns clio.infra.node-durability-test
  "Node schema publication must reach stable storage before a dependent ledger append."
  (:require [cljs.test :refer [deftest is]]
            [clio.extern.js.fs :as fs]
            [clio.extern.js.fs-observer :as observer]
            [clio.extern.js.runtime :as host]
            [clio.infra.event :as event]
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

(deftest public-append-preserves-symbolic-link-parent-path-semantics
  (doseq [relative? [false true]]
    (with-directory
      (fn [directory]
        (fs/ensure-dir! (str directory "/a"))
        (fs/ensure-dir! (str directory "/b/deep"))
        (observer/symbolic-link! (str directory "/b/deep") (str directory "/a/link"))
        (let [intended (str directory "/b/events.edn") lexical (str directory "/a/events.edn")
              rt (runtime/open (str directory "/schemas") fixture/catalog)
              supplied (str (if relative? (observer/relative-to-cwd directory) directory) "/a/link/../events.edn")]
          (ledger/create-ledger! intended)
          (ledger/create-ledger! lexical)
          (let [result (runtime/append! rt supplied :record/observed fixture/facts)]
            (is (= [(:event result)] (ledger/read-ledger intended)))
            (is (= [] (ledger/read-ledger lexical)) "Lexical '..' normalization must not redirect an append")))))))

(deftest public-append-exposes-the-exact-event-after-uncertain-durability
  (doseq [phase [:inode :parent]]
    (with-directory
      (fn [directory]
        (let [path (str directory "/events.edn")
              rt (runtime/open (str directory "/schemas") fixture/catalog)]
          (ledger/create-ledger! path)
          (let [cause (try
                        (observer/with-observer
                          #(when (= (if (= phase :inode) path directory) (:path %))
                             (throw (ex-info "Injected public append force failure" {:injected true})))
                          #(runtime/append! rt path :record/observed fixture/facts))
                        nil (catch :default error error))
                visible (ledger/read-ledger path)
                recovery (:clio/append-recovery (ex-data cause))]
            (is (some? cause) "Uncertain durability remains a failure")
            (is (= 1 (count visible)) "The actual write is already visible")
            (is (= {:ledger/path path :event (first visible)} recovery)
                "The public error must preserve generated UUID, timestamp and exact event")
            (when recovery
              (let [retry-cause (try
                                  (observer/with-observer
                                    #(when (= (if (= phase :inode) path directory) (:path %))
                                       (throw (ex-info "Still refusing public retry force" {:injected true})))
                                    #(runtime/retry-append! rt recovery))
                                  nil (catch :default error error))
                    trace (atom [])]
                (is (some? retry-cause) "A persistent force failure is not acknowledged")
                (is (= recovery (:clio/append-recovery (ex-data retry-cause))))
                (observer/with-observer #(swap! trace conj (:path %))
                  #(let [result (runtime/retry-append! {:schema/directory (str directory "/schemas")} recovery)]
                     (is (= :already-present (:append/result result)))
                     (is (= (:event recovery) (:event result)))))
                (is (= [path directory] @trace) "Successful retry forces both durability fences")
                (is (= visible (ledger/read-ledger path)))
                (is (= :clio.ledger/id-collision
                       (error-code #(runtime/retry-append! rt (assoc-in recovery [:event :event/data :amount] 8)))))
                (is (= visible (ledger/read-ledger path)))))))))))

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

(deftest exact-event-retry-refuses-unflushed-visible-content
  (with-directory
    (fn [directory]
      (let [path (str directory "/events.edn")
            runtime (runtime/open (str directory "/schemas") fixture/catalog)
            revisions (:schema/revisions runtime)
            event (event/make-event (:schema/current runtime) :record/observed fixture/facts)
            attempts (atom 0)]
        (ledger/create-ledger! path)
        (observer/with-observer
          (fn [operation]
            (when (= path (:path operation))
              (swap! attempts inc)
              (throw (ex-info "Injected ledger fsync failure" {:clio/error :injected-sync}))))
          (fn []
            (is (= :injected-sync (error-code #(ledger/append-event! revisions path event))))
            (is (= [event] (ledger/read-ledger path)) "Visibility is not a durable acknowledgment")
            (is (= :injected-sync (error-code #(ledger/append-event! revisions path event)))
                "An exact retry cannot acknowledge the still unflushed event")
            (is (= 2 @attempts))))
        (let [synced (atom [])]
          (observer/with-observer #(swap! synced conj %)
            #(is (= :already-present (ledger/append-event! revisions path event))))
          (is (= [path directory] (mapv :path @synced)))
          (is (= [event] (ledger/read-ledger path))))))))

(deftest leftover-created-ledgers-must-force-inode-and-parent-before-acknowledgment
  (doseq [phase [:inode :parent]]
    (with-directory
      (fn [directory]
        (let [path (str directory "/events.edn")
              runtime (runtime/open (str directory "/schemas") fixture/catalog)
              revisions (:schema/revisions runtime)
              inode-seen? (atom false)]
          (is (some?
                (error-code
                  #(observer/with-observer
                     (fn [operation]
                       (when (= path (:path operation)) (reset! inode-seen? true))
                       (when (and @inode-seen?
                                  (if (= phase :inode)
                                    (= path (:path operation))
                                    (= directory (:path operation))))
                         (throw (ex-info "Injected creation force refusal" {:clio/error :injected-sync}))))
                     (fn [] (ledger/create-ledger! path))))))
          (is (fs/exists? path) "An uncertain creation preserves the visible inode")
          (is (= :clio.fs/directory-sync-unavailable
                 (error-code
                   #(observer/with-observer
                      (fn [operation]
                        (when (= directory (:path operation))
                          (throw (ex-info "Injected retry parent force refusal" {}))))
                      (fn [] (ledger/ensure-durable! revisions path)))))
              "A validated empty ledger cannot bypass a failed parent fence")
          (let [trace (atom [])]
            (observer/with-observer #(swap! trace conj %)
              #(is (= path (ledger/ensure-durable! revisions path))))
            (is (= [path directory] (mapv :path @trace))))
          (let [fact (event/make-event (:schema/current runtime) :record/observed fixture/facts)]
            (is (= :clio.fs/directory-sync-unavailable
                   (error-code
                     #(observer/with-observer
                        (fn [operation]
                          (when (= directory (:path operation))
                            (throw (ex-info "Injected append parent force refusal" {}))))
                        (fn [] (ledger/append-event! revisions path fact)))))
                "New appends must preserve the parent fence too")
            (is (= :already-present (ledger/append-event! revisions path fact)))
            (is (= [fact] (ledger/read-ledger path)))))))))
