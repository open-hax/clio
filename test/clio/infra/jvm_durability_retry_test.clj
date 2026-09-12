(ns clio.infra.jvm-durability-retry-test
  (:require [clio.extern.jvm.fs :as fs]
            [clio.extern.jvm.runtime :as host]
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
