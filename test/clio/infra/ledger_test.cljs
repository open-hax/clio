(ns clio.infra.ledger-test
  (:require [cljs.test :refer [async deftest is testing]]
            [clio.extern.js.fs :as fs]
            [clio.extern.js.process :as process]
            [clio.extern.js.runtime :as host]
            [clio.infra.event :as event]
            [clio.infra.ledger :as ledger]
            [clio.infra.projection :as projection]
            [clio.infra.runtime :as runtime]
            [clio.law.schema :as schema-law]
            [clojure.string :as str]
            [promesa.core :as p]))

(def catalog
  {:counter/opened
   (schema-law/event-schema
    :counter/opened
    [:map {:closed true} [:amount :int]])
   :counter/added
   (schema-law/event-schema
    :counter/added
    [:map {:closed true} [:amount :int]])})

(defn apply-counter
  [state event]
  (case (:event/type event)
    :counter/opened (assoc state (:event/stream event)
                           (get-in event [:event/data :amount]))
    :counter/added (update state (:event/stream event) +
                           (get-in event [:event/data :amount]))
    state))

(defn error-code
  [f]
  (try
    (f)
    nil
    (catch :default cause
      (:clio/error (ex-data cause)))))

(defn error-message
  "The message of whatever f throws. Host errors such as ENOENT carry no
   :clio/error, so error-code cannot see them."
  [f]
  (try
    (f)
    nil
    (catch :default cause
      (ex-message cause))))

(deftest append-and-projection-are-idempotent
  (let [directory (str "/tmp/clio-" (host/random-uuid))
        schema-directory (str directory "/schemas")
        ledger-file (str directory "/events.edn")
        projection-file (str directory "/counter.edn")]
    (try
      (fs/ensure-dir! directory)
      (ledger/create-ledger! ledger-file)
      (let [rt (runtime/open schema-directory catalog)
            first-write
            (runtime/append!
             rt ledger-file :counter/opened
             {:event/stream "counter:a"
              :event/seq 1
              :event/actor "user:alice"
              :event/subject "counter:a"
              :event/data {:amount 10}})
            first-event (:event first-write)
            second-write
            (runtime/append!
             rt ledger-file :counter/added
             {:event/stream "counter:a"
              :event/seq 2
              :event/causes [(:event/id first-event)]
              :event/actor "user:alice"
              :event/subject "counter:a"
              :event/data {:amount 5}})
            refreshed (runtime/refresh rt)
            retry-result
            (ledger/append-event!
             (:schema/revisions refreshed)
             ledger-file
             (:event second-write))
            options
            {:revisions (:schema/revisions refreshed)
             :ledger-files [ledger-file]
             :output-file projection-file
             :initial {}
             :apply-event apply-counter}
            first-projection (projection/project-files! options)
            second-projection (projection/project-files! options)]
        (testing "schema revisions are persisted automatically"
          (is (= 1 (count (fs/list-files schema-directory)))))
        (testing "retrying an identical append does not duplicate history"
          (is (= :already-present retry-result))
          (is (= 2 (count (ledger/read-ledger ledger-file)))))
        (testing "projection extraction is a pure replay and atomic replacement"
          (is (= first-projection second-projection))
          (is (= {"counter:a" 15}
                 (:projection/state second-projection)))
          (is (fs/exists? projection-file))))
      (finally
        (fs/remove-tree! directory)))))

(deftest ledger-lines-accept-exactly-one-edn-form
  (let [directory (str "/tmp/clio-" (host/random-uuid))
        ledger-file (str directory "/events.edn")]
    (try
      (fs/ensure-dir! directory)
      (fs/write-text! ledger-file "{:a 1} {:b 2}\n")
      (is (= :clio.ledger/invalid-edn
             (error-code #(ledger/read-ledger ledger-file))))
      (finally
        (fs/remove-tree! directory)))))

(deftest append-repairs-a-missing-record-delimiter
  (let [directory (str "/tmp/clio-" (host/random-uuid))
        schema-directory (str directory "/schemas")
        ledger-file (str directory "/events.edn")]
    (try
      (fs/ensure-dir! directory)
      (ledger/create-ledger! ledger-file)
      (let [rt (runtime/open schema-directory catalog)
            first-write
            (runtime/append!
             rt ledger-file :counter/opened
             {:event/stream "counter:a"
              :event/seq 1
              :event/actor "user:alice"
              :event/subject "counter:a"
              :event/data {:amount 1}})
            first-event (:event first-write)
            without-newline
            (subs (fs/read-text ledger-file)
                  0
                  (dec (count (fs/read-text ledger-file))))]
        (fs/write-text! ledger-file without-newline)
        (runtime/append!
         rt ledger-file :counter/added
         {:event/stream "counter:a"
          :event/seq 2
          :event/causes [(:event/id first-event)]
          :event/actor "user:alice"
          :event/subject "counter:a"
          :event/data {:amount 2}})
        (is (= 2 (count (ledger/read-ledger ledger-file)))))
      (finally
        (fs/remove-tree! directory)))))

(deftest concurrent-processes-lock-the-ledger-inode
  (async done
    (let [directory (str "/tmp/clio-" (host/random-uuid))
          schema-directory (str directory "/schemas")
          ledger-file (str directory "/events.edn")
          ledger-alias (str directory "/events-hardlink.edn")
          finish (fn []
                   (fs/remove-tree! directory)
                   (done))]
      (try
        (fs/ensure-dir! directory)
        (ledger/create-ledger! ledger-file)
        (fs/hard-link! ledger-file ledger-alias)
        (let [rt (runtime/open schema-directory catalog)
              revision (:schema/current rt)
              event-a
              (event/make-event
               revision :counter/opened
               {:event/stream "counter:race"
                :event/seq 1
                :event/actor "worker:a"
                :event/subject "counter:race"
                :event/data {:amount 1}})
              event-b
              (event/make-event
               revision :counter/opened
               {:event/stream "counter:race"
                :event/seq 1
                :event/actor "worker:b"
                :event/subject "counter:race"
                :event/data {:amount 2}})
              worker (str (process/cwd) "/test/clio/infra/append_worker.nbb")
              start-at (+ (process/now-ms) 3000)
              command
              (fn [path event]
                {:command "pnpm"
                 :cwd (process/cwd)
                 ;; The workspace nbb; see kernel-lock-test.
                 :args ["exec" "nbb" worker path (pr-str event) (pr-str start-at)]})
              workflow
              (p/let [results
                      (process/run-concurrently!
                       [(command ledger-file event-a)
                        (command ledger-alias event-b)])]
                (let [exit-codes (sort (map :exit-code results))
                      output (str/join "\n" (map :stdout results))]
                  (testing "only one colliding process commits"
                    (is (= [0 1] exit-codes))
                    (is (str/includes? output ":clio.ledger/concurrent-stream-write")))
                  (testing "hard-link aliases share one authoritative inode lock"
                    (is (= 1 (count (ledger/read-ledger ledger-file))))
                    (is (= (ledger/read-ledger ledger-file)
                           (ledger/read-ledger ledger-alias))))
                  (finish)))]
          (p/catch workflow
                   (fn [cause]
                     (is false (str "concurrent append probe failed: " cause))
                     (finish))))
        (catch :default cause
          (is false (str "concurrent append setup failed: " cause))
          (finish))))))

(deftest append-refuses-to-create-a-missing-ledger
  ;; `a+` would create the file, so a misspelled or deleted ledger path would
  ;; quietly become a fresh empty history while the intended ledger stayed
  ;; behind. `create-ledger!` is the only creation path.
  (let [directory (str "/tmp/clio-" (host/random-uuid))
        schema-directory (str directory "/schemas")
        missing-file (str directory "/typo-events.edn")]
    (try
      (fs/ensure-dir! directory)
      (let [rt (runtime/open schema-directory catalog)]
        (testing "the append is refused by name"
          (is (= :clio.ledger/missing-file
                 (error-code
                  #(runtime/append!
                    rt missing-file :counter/opened
                    {:event/stream "counter:a"
                     :event/seq 1
                     :event/actor "user:alice"
                     :event/subject "counter:a"
                     :event/data {:amount 1}})))))
        (testing "and the ledger is still absent afterwards"
          (is (not (fs/exists? missing-file)))))
      (finally
        (fs/remove-tree! directory)))))

(deftest acquiring-a-lock-refuses-to-create-a-missing-ledger
  ;; The same guarantee one layer down: the lock open carries no O_CREAT, so
  ;; even a caller bypassing clio.infra.ledger cannot conjure a ledger.
  (let [directory (str "/tmp/clio-" (host/random-uuid))
        missing-file (str directory "/absent.edn")]
    (try
      (fs/ensure-dir! directory)
      (is (str/includes? (str (error-message #(fs/acquire-lock! missing-file)))
                         "ENOENT")
          "the open fails at the syscall rather than creating the ledger")
      (is (not (fs/exists? missing-file)))
      (finally
        (fs/remove-tree! directory)))))

(deftest a-locked-ledger-refuses-path-based-reads
  ;; A POSIX record lock is released when the process closes ANY descriptor
  ;; for the file, so a path-based read inside a critical section would drop
  ;; the lock silently. The guard turns that into a loud failure.
  (let [directory (str "/tmp/clio-" (host/random-uuid))
        ledger-file (str directory "/events.edn")]
    (try
      (fs/ensure-dir! directory)
      (fs/create-exclusive! ledger-file)
      (let [lock (fs/acquire-lock! ledger-file)]
        (testing "while the lock is held"
          (is (= :clio.fs/locked-path-read
                 (error-code #(fs/read-text ledger-file))))
          (testing "the lock-owning descriptor still reads"
            (is (= "" (fs/read-locked-text lock)))))
        (fs/release-lock! lock))
      (testing "and the path reads normally once released"
        (is (= "" (fs/read-text ledger-file))))
      (finally
        (fs/remove-tree! directory)))))
