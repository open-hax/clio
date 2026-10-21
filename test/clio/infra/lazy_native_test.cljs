(ns clio.infra.lazy-native-test
  (:require [cljs.test :refer [async deftest is testing]]
            [clio.extern.js.fs :as fs]
            [clio.extern.js.process :as process]
            [clio.extern.js.runtime :as host]
            [clio.shape.edn :as edn]
            [promesa.core :as p]))

(deftest importing-ledger-stack-does-not-load-native-binding
  (async done
    (let [directory (str "/tmp/clio-lazy-native-" (host/random-uuid))
          ledger-file (str directory "/events.edn")
          finish (fn [] (fs/remove-tree! directory) (done))]
      (fs/ensure-dir! directory)
      (fs/create-exclusive! ledger-file)
      (-> (p/let [result (process/run-command!
                          {:command "pnpm"
                           :cwd (process/cwd)
                           :args ["exec" "nbb" "-cp" "src:test"
                                  (str (process/cwd) "/test/clio/infra/lazy_native_probe.nbb")
                                  ledger-file]})
                  report (edn/read-one (:stdout result))]
            (testing "the probe exits cleanly"
              (is (zero? (:exit-code result)) (:stderr result)))
            (testing "namespace load leaves the binding unloaded and unmapped"
              (is (= [false false] (:before report))))
            (testing "the first lock loads and maps the binding"
              (is (= [true true] (:after report))))
            (finish))
          (p/catch (fn [cause]
                     (is false (str "lazy native probe failed: " cause))
                     (finish)))))))

(deftest preload-resolves-and-is-idempotent
  (async done
    (-> (p/let [first-load (fs/load-native-locks!)
                second-load (fs/load-native-locks!)]
          (is (true? first-load))
          (is (true? second-load))
          (is (true? (fs/native-locks-loaded?)))
          (done))
        (p/catch (fn [cause] (is false (str cause)) (done))))))
