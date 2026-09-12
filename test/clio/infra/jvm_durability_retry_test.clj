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
