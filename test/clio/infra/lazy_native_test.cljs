(ns clio.infra.lazy-native-test
  "Mapping evidence is Linux-only (/proc/self/maps); elsewhere the probe
   reports nil for it and only Clio's loaded flag is asserted."
  (:require [cljs.test :refer [async deftest is testing]]
            [clio.extern.js.fs :as fs]
            [clio.extern.js.fs-observer :as observer]
            [clio.extern.js.process :as process]
            [clio.extern.js.runtime :as host]
            [clio.shape.edn :as edn]
            [promesa.core :as p]))

(defn- expected [loaded?]
  [loaded? (when (observer/maps-available?) loaded?)])

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
              (is (= (expected false) (:before report))))
            (testing "the first lock loads and maps the binding"
              (is (= (expected true) (:after report))))
            (finish))
          (p/catch (fn [cause]
                     (is false (str "lazy native probe failed: " cause))
                     (finish)))))))

(deftest lazy-binding-resolves-from-the-script-not-the-caller-cwd
  (async done
    (let [directory (str "/tmp/clio-lazy-cwd-" (host/random-uuid))
          ledger-file (str directory "/events.edn")
          root (process/cwd)
          finish (fn [] (fs/remove-tree! directory) (done))]
      (fs/ensure-dir! directory)
      (fs/create-exclusive! ledger-file)
      ;; cwd has no node_modules: only the script's install location does.
      (-> (p/let [result (process/run-command!
                          {:command "node"
                           :cwd directory
                           :args [(str root "/node_modules/nbb/cli.js")
                                  "-cp" (str root "/src:" root "/test")
                                  (str root "/test/clio/infra/lazy_native_probe.nbb")
                                  ledger-file]})
                  report (edn/read-one (:stdout result))]
            (is (zero? (:exit-code result)) (:stderr result))
            (is (= (expected true) (:after report)))
            (finish))
          (p/catch (fn [cause]
                     (is false (str "out-of-tree probe failed: " cause))
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
