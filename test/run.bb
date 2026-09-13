#!/usr/bin/env bb

(require '[clojure.test :as test]
         '[clio.law.ledger-test]
         '[clio.lint-extern-boundary-test]
         '[clio.shape.canonical-test])

(let [{:keys [fail error]}
      (test/run-tests 'clio.shape.canonical-test
                      'clio.law.ledger-test
                      'clio.lint-extern-boundary-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
