(ns clio.infra.jvm-ledger-test
  (:require [clio.extern.jvm.fs :as fs]
            [clio.extern.jvm.runtime :as host]
            [clio.extern.jvm.test-support :as support]
            [clio.infra.event :as event]
            [clio.infra.host-fixture :as fixture]
            [clio.infra.ledger :as ledger]
            [clio.infra.projection :as projection]
            [clio.infra.runtime :as runtime]
            [clio.infra.schema-store :as schema-store]
            [clio.shape.canonical :as canonical]
            [clio.shape.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn error-code [f]
  (try (f) nil (catch Exception cause (:clio/error (ex-data cause)))))

(defn with-ledger [f]
  (let [directory (str "/tmp/clio-jvm-" (host/random-uuid))
        path (str directory "/events.edn")
        schemas (str directory "/schemas")]
    (try
      (fs/ensure-dir! directory)
      (ledger/create-ledger! path)
      (f {:directory directory :path path :schemas schemas
          :runtime (runtime/open schemas fixture/catalog)})
      (finally (fs/remove-tree! directory)))))

(defn reduce-amount [state evt]
  (+ state (get-in evt [:event/data :amount])))

(deftest jvm-canonical-snapshots-release-the-owning-channel-on-read-failure
  (with-ledger
    (fn [{:keys [path runtime]}]
      (let [fact (:event (runtime/append! runtime path :record/observed fixture/facts))
            revisions (:schema/revisions runtime)
            releases (atom 0)
            release! fs/release-lock!]
        (with-redefs [fs/read-text (fn [_] (throw (ex-info "Unlocked path read" {:clio/error :unlocked-read})))]
          (is (= {:events [fact]}
                 (try {:events (:canonical/events (ledger/canonicalize-files revisions [path]))}
                      (catch Exception cause {:error (:clio/error (ex-data cause))})))))
        (with-redefs [fs/read-locked-text (fn [_] (throw (ex-info "Injected snapshot read failure" {:clio/error :injected-read})))
                      fs/release-lock! (fn [lock] (swap! releases inc) (release! lock))]
          (is (= :injected-read (error-code #(ledger/canonicalize-files revisions [path]))))
          (is (= 1 @releases)))
        (is (= [fact] (:canonical/events (ledger/canonicalize-files revisions [path]))))))))

(deftest jvm-restart-rebuilds-typed-events-and-projections
  (with-ledger
    (fn [{:keys [directory path schemas runtime]}]
      (let [{evt :event} (runtime/append! runtime path :record/observed fixture/facts)
            reopened (runtime/open schemas fixture/catalog)
            options {:revisions (:schema/revisions reopened) :ledger-files [path]
                     :output-file (str directory "/projection.edn")
                     :initial 0 :apply-event reduce-amount}]
        (is (= :already-present (ledger/append-event! (:schema/revisions reopened) path evt)))
        (is (= [evt] (ledger/read-ledger path)))
        (is (= fixture/payload (:event/data (first (ledger/read-ledger path)))))
        (is (re-matches #".*\.\d{3}Z" (:event/at evt)))
        (is (= (projection/project-files! options) (projection/project-files! options)))
        (is (= 7 (:projection/state (edn/read-one (fs/read-text (:output-file options))))))))))

(deftest jvm-preserves-admission-and-fail-closed-history
  (with-ledger
    (fn [{:keys [directory path runtime]}]
      (let [{evt :event} (runtime/append! runtime path :record/observed fixture/facts)
            revisions (:schema/revisions runtime)
            missing (str directory "/missing.edn")]
        (is (= :clio.ledger/id-collision
               (error-code #(ledger/append-event! revisions path
                                                  (assoc-in evt [:event/data :amount] 8)))))
        (is (= :clio.ledger/concurrent-stream-write
               (error-code #(ledger/append-event! revisions path
                                                  (assoc evt :event/id (host/random-uuid))))))
        (is (= :clio.ledger/missing-file
               (error-code #(ledger/append-event! revisions missing evt))))
        (is (= :clio.ledger/missing-file
               (error-code #(ledger/canonicalize-files revisions [path missing]))))
        (is (not (fs/exists? missing)))
        (is (= :clio.fs/not-regular-file (error-code #(fs/acquire-lock! directory))))
        (fs/write-text! path "{:first 1} {:second 2}\n")
        (is (= :clio.ledger/invalid-edn (error-code #(ledger/read-ledger path))))))))

(deftest jvm-rejects-corrupt-schema-snapshots
  (with-ledger
    (fn [{:keys [schemas runtime]}]
      (let [root (get-in runtime [:schema/current :schema/root])
            path (schema-store/revision-path schemas root)]
        (fs/write-text! path (pr-str {:schema/root root :schema/catalog {}}))
        (is (= :clio.schema-store/corrupt-snapshot
               (error-code #(schema-store/load-revisions schemas))))))))

(deftest jvm-path-guard-protects-hard-link-aliases
  (with-ledger
    (fn [{:keys [directory path]}]
      (let [alias (str directory "/alias.edn")]
        (fs/hard-link! path alias)
        (let [lock (fs/acquire-lock! path)]
          (try
            (is (= "" (fs/read-locked-text lock)))
            (is (= :clio.fs/locked-path-read (error-code #(fs/read-text path))))
            (is (= :clio.fs/locked-path-read (error-code #(fs/read-text alias))))
            (is (= :clio.fs/locked-path-read (error-code #(fs/acquire-lock! alias))))
            (finally (fs/release-lock! lock))))
        (is (= "" (fs/read-text alias)))))))

(deftest malformed-host-values-are-not-canonical-edn
  (is (= :clio.canonical/invalid-instant
         (error-code #(canonical/canonical-edn (support/invalid-instant)))))
  (is (= :clio.canonical/unsupported-value
         (error-code #(canonical/canonical-edn (support/non-edn-instant)))))
  (is (= :clio.canonical/unsupported-value
         (error-code #(canonical/canonical-edn (support/arbitrary-object))))))

(deftest schema-rename-forces-the-directory-before-acknowledgement
  (with-ledger
    (fn [{:keys [directory]}]
      (let [from (str directory "/pending.edn")
            to (str directory "/committed.edn")
            synchronize! fs/sync-directory!
            observations (atom [])]
        (fs/write-text! from "{:schema :new}")
        (with-redefs [fs/sync-directory!
                      (fn [path]
                        (swap! observations conj [(fs/exists? from) (fs/exists? to)])
                        (synchronize! path))]
          (is (= to (fs/rename! from to))))
        (is (= [[true false] [false true]] @observations))
        (is (= "{:schema :new}" (fs/read-text to)))))))

(deftest unsupported-directory-sync-refuses-before-changing-schema-paths
  (with-ledger
    (fn [{:keys [directory]}]
      (let [from (str directory "/pending.edn") to (str directory "/committed.edn")]
        (fs/write-text! from "{:schema :new}")
        (with-redefs [fs/sync-directory!
                      (fn [_] (throw (ex-info "unsupported directory force"
                                              {:clio/error :clio.fs/directory-sync-unavailable})))]
          (is (= :clio.fs/directory-sync-unavailable (error-code #(fs/rename! from to)))))
        (is (fs/exists? from))
        (is (not (fs/exists? to)))))))

(deftest node-and-jvm-share-schemas-edn-tags-and-replay
  (with-ledger
    (fn [{:keys [directory path schemas runtime]}]
      (let [evt (:event (runtime/append! runtime path :record/observed fixture/facts))
            output (str directory "/peer.out")
            peer (support/start! ["nbb" "-cp" "test" "test/clio/infra/jvm_peer.nbb"
                                  "read" schemas path] output)]
        (try
          (is (= 0 (support/finish! peer)) (fs/read-text output))
          (let [result (edn/read-one (str/trim (fs/read-text output)))]
            (is (= (get-in runtime [:schema/current :schema/root]) (:root result)))
            (is (= [evt] (:events result)))
            (is (= (projection/build
                    (ledger/canonicalize-files (:schema/revisions runtime) [path])
                    0 reduce-amount)
                   (:projection result))))
          (finally (support/stop! peer)))))))

(deftest jvm-reopens-node-created-durable-schema-and-ledger
  (let [directory (str "/tmp/clio-node-first-" (host/random-uuid))
        child (str directory "/node-created")
        output (str directory "/peer.out")]
    (try
      (fs/ensure-dir! directory)
      (let [peer (support/start! ["nbb" "-cp" "test" "test/clio/infra/durability_peer.nbb" child] output)]
        (try
          (is (= 0 (support/finish! peer)) (fs/read-text output))
          (let [result (edn/read-one (str/trim (fs/read-text output)))
                reopened (runtime/open (str child "/nested/schemas") fixture/catalog)
                path (str child "/events.edn")
                events (:canonical/events (ledger/canonicalize-files (:schema/revisions reopened) [path]))]
            (is (= :appended (:append/result result)))
            (is (= [(:event result)] events))
            (is (= fixture/payload (:event/data (first events))))
            (is (= (get-in reopened [:schema/current :schema/root])
                   (get-in result [:event :event/schema :schema/root]))))
          (finally (support/stop! peer))))
      (finally (fs/remove-tree! directory)))))

(deftest node-replays-both-admitted-calendar-boundaries-from-jvm
  (doseq [millis [-12219292800000 253402300799999]]
    (with-ledger
      (fn [{:keys [directory path schemas runtime]}]
        (let [facts (assoc-in fixture/facts [:event/data :observed/at] (support/instant-at millis))
              evt (:event (runtime/append! runtime path :record/observed facts))
              output (str directory "/boundary-peer.out")
              peer (support/start! ["nbb" "-cp" "test" "test/clio/infra/jvm_peer.nbb"
                                    "read" schemas path] output)]
          (try
            (is (= 0 (support/finish! peer)) (fs/read-text output))
            (let [result (edn/read-one (str/trim (fs/read-text output)))]
              (is (= [evt] (:events result)))
              (is (= millis (inst-ms (get-in result [:events 0 :event/data :observed/at])))))
            (finally (support/stop! peer))))))))

(deftest node-contender-blocks-on-jvm-inode-lock
  (with-ledger
    (fn [{:keys [directory path schemas runtime]}]
      (let [revision (:schema/current runtime)
            winner (event/make-event revision :record/observed fixture/facts)
            contender (event/make-event revision :record/observed fixture/facts)
            alias (str directory "/alias.edn")
            ready (str directory "/ready")
            output (str directory "/peer.out")
            _ (fs/hard-link! path alias)
            lock (fs/acquire-lock! path)
            peer (support/start! ["nbb" "-cp" "test" "test/clio/infra/jvm_peer.nbb"
                                  "append" schemas alias ready (pr-str contender)] output)]
        (try
          (try
            (support/wait-for-path! ready)
            (is (not (support/exited? peer 250)) "Node must wait for JVM's fcntl inode lock")
            (fs/append-locked-text! lock (str (pr-str winner) "\n"))
            (finally (fs/release-lock! lock)))
          (testing "the loser observes the committed event after acquiring the lock"
            (is (= 1 (support/finish! peer)))
            (is (str/includes? (fs/read-text output) ":clio.ledger/concurrent-stream-write"))
            (is (= [winner] (ledger/read-ledger path))))
          (finally (support/stop! peer)))))))

(deftest jvm-contender-blocks-on-node-inode-lock
  (with-ledger
    (fn [{:keys [directory path runtime]}]
      (let [ready (str directory "/ready")
            output (str directory "/peer.out")
            peer (support/start! ["nbb" "test/clio/infra/lock_holder.nbb"
                                  path ready "800"] output)]
        (try
          (support/wait-for-path! ready)
          (let [started (support/now-ms)]
            (is (= :appended
                   (:append/result (runtime/append! runtime path :record/observed fixture/facts))))
            (is (>= (- (support/now-ms) started) 400)
                "JVM must wait for Node's fcntl inode lock"))
          (is (= 0 (support/finish! peer)) (fs/read-text output))
          (finally (support/stop! peer)))))))

(deftest jvm-single-reader-waits-for-node-to-complete-its-locked-append
  (with-ledger
    (fn [{:keys [directory path runtime]}]
      (let [fact (event/make-event (:schema/current runtime) :record/observed fixture/facts)
            partial-ready (str directory "/partial-ready")
            reader-ready (str directory "/reader-ready")
            output (str directory "/writer.out")
            peer (support/start! ["nbb" "-cp" "test" "test/clio/infra/partial_writer.nbb"
                                  path partial-ready reader-ready (pr-str fact)] output)
            read-text fs/read-text
            acquire-lock! fs/acquire-lock!
            signal! (fn [candidate]
                      (when (= path candidate)
                        (fs/write-text! reader-ready "reading")))]
        (try
          (support/wait-for-path! partial-ready)
          (let [started (support/now-ms)
                result (with-redefs [fs/read-text (fn [candidate]
                                                   (signal! candidate) (read-text candidate))
                                    fs/acquire-lock! (fn [candidate]
                                                       (signal! candidate) (acquire-lock! candidate))]
                         (try {:events (ledger/read-ledger path)}
                              (catch Exception cause {:error (:clio/error (ex-data cause))})))]
            (is (= {:events [fact]} result)
                "The public singular reader must not parse a writer's incomplete final line")
            (is (>= (- (support/now-ms) started) 200)
                "Reading waits until the peer completes its append and releases the inode"))
          (is (= 0 (support/finish! peer)) (fs/read-text output))
          (is (= [fact] (ledger/read-ledger path)))
          (finally (support/stop! peer)))))))

(deftest node-single-reader-waits-for-jvm-to-complete-its-locked-append
  (with-ledger
    (fn [{:keys [directory path schemas runtime]}]
      (let [fact (event/make-event (:schema/current runtime) :record/observed fixture/facts)
            text (pr-str fact)
            split (quot (count text) 2)
            ready (str directory "/reader-ready")
            output (str directory "/reader.out")
            lock (fs/acquire-lock! path)
            peer (atom nil)]
        (try
          (try
            (fs/append-locked-text! lock (subs text 0 split))
            (reset! peer (support/start! ["nbb" "-cp" "test" "test/clio/infra/jvm_peer.nbb"
                                         "read-single" schemas path ready] output))
            (support/wait-for-path! ready)
            (is (not (support/exited? @peer 250))
                "Node's singular reader must wait on the JVM's actual POSIX inode lock")
            (fs/append-locked-text! lock (str (subs text split) "\n"))
            (finally (fs/release-lock! lock)))
          (is (= 0 (support/finish! @peer)) (fs/read-text output))
          (is (= {:events [fact]} (edn/read-one (str/trim (fs/read-text output)))))
          (is (= [fact] (ledger/read-ledger path)))
          (finally (when @peer (support/stop! @peer))))))))
