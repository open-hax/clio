(ns clio.infra.cli-test
  "Actual published CLI behavior while another process publishes a new schema and event."
  (:require [cljs.test :as test]
            [clio.extern.js.cli-process :as cli-process]
            [clio.extern.js.fs :as fs]
            [clio.extern.js.process :as process]
            [clio.extern.js.runtime :as host]
            [clio.infra.ledger :as ledger]
            [clio.infra.runtime :as runtime]
            [clio.law.schema :as schema-law]
            [clio.shape.edn :as edn]))

(defn- ^:async run-cli-schema-race!
  []
  (let [directory (str "/tmp/clio-cli-schema-" (host/random-uuid))
        schema-dir (str directory "/schemas") file (str directory "/events.edn")
        ready (str directory "/reader-ready") release (str directory "/reader-release")
        old-catalog {:old/recorded (schema-law/event-schema :old/recorded [:map [:amount :int]])}
        new-catalog (assoc old-catalog :new/recorded (schema-law/event-schema :new/recorded [:map [:text :string]]))
        child (atom nil)]
    (try
      (fs/ensure-dir! directory)
      (runtime/open schema-dir old-catalog)
      (ledger/create-ledger! file)
      (reset! child (cli-process/start! {:root (process/cwd) :args ["canonicalize" schema-dir file]
                                         :file file :ready ready :release release}))
      (await (fs/wait-for-exists! ready 20000))
      ;; A different process from the paused CLI publishes immutable schema first,
      ;; then its referencing event through actual canonical admission and fsync.
      (let [writer (runtime/open schema-dir new-catalog)
            event (:event (runtime/append! writer file :new/recorded
                                           {:event/stream "cli:race" :event/seq 1 :event/actor "writer"
                                            :event/subject "schema race" :event/data {:text "published during read"}}))]
        (fs/write-text! release "continue")
        (let [result (await (cli-process/finish! @child))]
          (test/is (= 0 (:exit-code result)) (pr-str result))
          (when (= 0 (:exit-code result))
            (let [canonical (edn/read-one (:stdout result))]
              (test/is (= [(:event/id event)] (:canonical/event-ids canonical)))
              (test/is (= [event] (:canonical/events canonical)))))))
      (finally
        (when @child (await (cli-process/stop! @child)))
        (fs/remove-tree! directory)))))

(defn- ^:async run-inherited-pipe-cleanup!
  []
  (let [directory (str "/tmp/clio-cli-cleanup-" (host/random-uuid))
        ready (str directory "/ready") child (atom nil)]
    (try
      (fs/ensure-dir! directory)
      (reset! child (cli-process/start-pipe-peer! (process/cwd) ready))
      (await (fs/wait-for-exists! ready 20000))
      (test/is (cli-process/leader-exited? @child) "The leader has exited while a descendant still owns stdout/stderr")
      (let [result (await (cli-process/stop! @child))]
        (test/is (false? (:timed-out? result)) "Explicit cleanup must close inherited pipes before the timeout backstop"))
      (finally
        (when @child (await (cli-process/stop! @child)))
        (fs/remove-tree! directory)))))

(defn- ^:async report-cli-test! [operation done]
  (try (await (operation))
       (catch :default error (test/is false (ex-message error)))
       (finally (done))))

(test/deftest canonicalize-command-loads-schemas-after-its-locked-ledger-snapshot
  ;; NBB's SCI deftest macro does not propagate ^:async onto its generated fn.
  ;; Keep the test work in native async functions and bridge only completion.
  (test/async done (report-cli-test! run-cli-schema-race! done)))

(test/deftest cli-cleanup-closes-inherited-pipes-after-the-group-leader-exits
  (test/async done (report-cli-test! run-inherited-pipe-cleanup! done)))
