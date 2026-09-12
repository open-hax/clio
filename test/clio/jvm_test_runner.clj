(ns clio.jvm-test-runner
  (:require [clio.domain.canonicalize-test]
            [clio.domain.schema-test]
            [clio.infra.jvm-ledger-test]
            [clio.law.ledger-test]
            [clio.lint-extern-boundary-test]
            [clio.shape.canonical-test]
            [clio.shape.edn-test]
            [clojure.test :as test]))

(defn -main [& _args]
  (let [{:keys [fail error]}
        (test/run-tests 'clio.domain.canonicalize-test
                        'clio.domain.schema-test
                        'clio.infra.jvm-ledger-test
                        'clio.law.ledger-test
                        'clio.lint-extern-boundary-test
                        'clio.shape.canonical-test
                        'clio.shape.edn-test)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (throw (ex-info "Clio JVM tests failed" {:fail fail :error error})))))
