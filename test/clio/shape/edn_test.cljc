(ns clio.shape.edn-test
  (:require [clio.shape.edn :as edn]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])))

(defn error-code
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) cause
      (:clio/error (ex-data cause)))))

(deftest reads-exactly-one-form
  (is (= {:a 1} (edn/read-one "{:a 1}")))
  (is (= :clio.edn/expected-one-form
         (error-code #(edn/read-one "{:a 1} {:b 2}"))))
  (is (= :clio.edn/expected-one-form
         (error-code #(edn/read-one "")))))

(deftest a-stray-delimiter-cannot-smuggle-trailing-content
  ;; A synthetic `[ ... ]` wrapper around raw text is unsound: an unescaped
  ;; `]` inside malformed input closes the wrapper early, and a reader that
  ;; only checks "did I get exactly one form back" never sees what follows.
  (is (= :clio.edn/expected-one-form
         (error-code #(edn/read-one "{:a 1}] ignored")))))

(deftest clojure-only-reader-literals-are-rejected
  ;; A ledger/schema-snapshot file only ever contains what pr-str on plain
  ;; data produces; Clojure's fn/deref/var/syntax-quote extensions are not
  ;; EDN and must not silently parse.
  (is (= :clio.edn/expected-one-form (error-code #(edn/read-one "~x"))))
  (is (= :clio.edn/expected-one-form (error-code #(edn/read-one "#(inc %)"))))
  (is (= :clio.edn/expected-one-form (error-code #(edn/read-one "@x"))))
  (is (= :clio.edn/expected-one-form (error-code #(edn/read-one "#'x")))))
