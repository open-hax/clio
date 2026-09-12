(ns clio.infra.jvm-durability-retry-test
  (:require [clio.extern.jvm.fs :as fs]
            [clio.extern.jvm.runtime :as host]
            [clio.infra.event :as event]
            [clio.infra.host-fixture :as fixture]
            [clio.infra.ledger :as ledger]
            [clio.infra.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(deftest retry-forces-existing-ancestry-after-interrupted-directory-creation
  (let [root (str "/tmp/clio-directory-retry-" (host/random-uuid))
        parent (str root "/nested")
        leaf (str parent "/schemas")
        real-sync! fs/sync-directory!
        refused* (atom false)
        observed* (atom [])]
    (try
      (with-redefs [fs/sync-directory!
                    (fn [directory]
                      (if (and (= parent directory) (compare-and-set! refused* false true))
                        (throw (ex-info "Injected parent force failure"
                                        {:clio/error :clio.fs/directory-sync-unavailable}))
                        (real-sync! directory)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected parent force failure"
                             (fs/ensure-dir! leaf))))
      (is (fs/exists? leaf) "mkdir succeeded even though durability was not acknowledged")
      (with-redefs [fs/sync-directory!
                    (fn [directory]
                      (swap! observed* conj directory)
                      (real-sync! directory))]
        (is (= leaf (fs/ensure-dir! leaf))))
      (is (every? (set @observed*) [leaf parent root "/tmp" "/"])
          "A retry must force already-created directories and their parent entries")
      (finally (fs/remove-tree! root)))))

(deftest visible-event-retries-must-force-the-owning-channel-again
  (let [root (str "/tmp/clio-event-retry-" (host/random-uuid))
        path (str root "/events.edn")
        forced (atom 0)]
    (try
      (fs/ensure-dir! root)
      (ledger/create-ledger! path)
      (let [runtime (runtime/open (str root "/schemas") fixture/catalog)
            revisions (:schema/revisions runtime)
            fact (event/make-event (:schema/current runtime) :record/observed fixture/facts)
            original-force! fs/force-file!]
        (with-redefs [fs/force-file! (fn [_] (swap! forced inc)
                                      (throw (ex-info "Injected event force failure" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected event force failure"
                               (ledger/append-event! revisions path fact)))
          (is (= [fact] (ledger/read-ledger path)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected event force failure"
                               (ledger/append-event! revisions path fact)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected event force failure"
                               (ledger/ensure-durable! revisions path)))
          (is (= 3 @forced)))
        (with-redefs [fs/force-file! (fn [channel] (swap! forced inc) (original-force! channel))]
          (is (= :already-present (ledger/append-event! revisions path fact)))
          (is (= path (ledger/ensure-durable! revisions path))))
        (is (= 5 @forced))
        (is (= [fact] (ledger/read-ledger path))))
      (finally (fs/remove-tree! root)))))

(deftest empty-ledger-forces-the-created-inode-before-the-parent
  (let [root (str "/tmp/clio-empty-force-" (host/random-uuid))
        file (str root "/events.edn")
        observed (atom [])
        real-file! fs/force-file!
        real-directory! fs/sync-directory!]
    (try
      (fs/ensure-dir! root)
      (with-redefs [fs/force-file! (fn [channel] (swap! observed conj :inode) (real-file! channel))
                    fs/sync-directory! (fn [path] (swap! observed conj :parent) (real-directory! path))]
        (is (= file (fs/create-exclusive! file))))
      (is (= [:inode :parent] @observed))
      (is (= "" (fs/read-text file)))
      (finally (fs/remove-tree! root)))))

(deftest failed-empty-inode-force-is-never-acknowledged
  (let [root (str "/tmp/clio-empty-force-failure-" (host/random-uuid))
        file (str root "/events.edn")
        parent-forced? (atom false)]
    (try
      (fs/ensure-dir! root)
      (with-redefs [fs/force-file! (fn [_] (throw (ex-info "Injected inode force failure" {})))
                    fs/sync-directory! (fn [_] (reset! parent-forced? true))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected inode force failure"
                             (fs/create-exclusive! file))))
      (is (false? @parent-forced?))
      (finally (fs/remove-tree! root)))))

(deftest leftover-created-ledger-must-force-the-inode-and-parent-on-retry
  (doseq [phase [:inode :parent]]
    (let [root (str "/tmp/clio-create-retry-" (host/random-uuid))
          file (str root "/events.edn")
          real-file! fs/force-file!
          real-directory! fs/sync-directory!
          trace (atom [])]
      (try
        (fs/ensure-dir! root)
        (let [runtime (runtime/open (str root "/schemas") fixture/catalog)
              revisions (:schema/revisions runtime)]
          (with-redefs [fs/force-file! (fn [channel]
                                        (if (= phase :inode)
                                          (throw (ex-info "Injected creation refusal" {}))
                                          (real-file! channel)))
                        fs/sync-directory! (fn [_] (throw (ex-info "Injected creation refusal" {})))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected creation refusal"
                                 (ledger/create-ledger! file))))
          (is (fs/exists? file))
          (with-redefs [fs/sync-directory! (fn [_] (throw (ex-info "Injected retry parent refusal" {})))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected retry parent refusal"
                                 (ledger/ensure-durable! revisions file))))
          (with-redefs [fs/force-file! (fn [channel] (swap! trace conj :inode) (real-file! channel))
                        fs/sync-directory! (fn [path] (swap! trace conj path) (real-directory! path))]
            (is (= file (ledger/ensure-durable! revisions file))))
          (is (= [:inode root] @trace))
          (let [fact (event/make-event (:schema/current runtime) :record/observed fixture/facts)]
            (with-redefs [fs/sync-directory! (fn [_] (throw (ex-info "Injected append parent refusal" {})))]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Injected append parent refusal"
                                   (ledger/append-event! revisions file fact))))
            (is (= :already-present (ledger/append-event! revisions file fact)))
            (is (= [fact] (ledger/read-ledger file)))))
        (finally (fs/remove-tree! root))))))
