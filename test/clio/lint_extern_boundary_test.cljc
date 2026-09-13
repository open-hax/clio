(ns clio.lint-extern-boundary-test
  "The boundary gate must key on forms, not on text. Each fixture below is
   source held as a string, so this file itself stays boundary-clean while
   describing sources that are not."
  (:require [clio.lint-extern-boundary :as boundary]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])))

(defn- violations
  [source]
  (vec (boundary/source-violations source)))

(deftest documentation-mentioning-host-interop-is-not-a-violation
  (testing "a comment, docstring, or string literal performs no interop"
    (is (= [] (violations
               (str "(ns clio.law.example)\n"
                    ";; Documentation only: raw js/console access belongs in extern.\n"
                    "(defn note\n"
                    "  \"Avoid #js literals and js/globals outside extern.js.*.\"\n"
                    "  []\n"
                    "  \"js/console.log and #js {:a 1} are data here\")\n"))))))

(deftest host-interop-in-code-is-a-violation
  (testing "a symbol in the js namespace"
    (is (= ["js/ interop js/console.log"]
           (violations "(ns clio.law.example)\n(defn boom [] (js/console.log \"x\"))\n"))))

  (testing "a #js literal"
    (is (= ["#js literal"]
           (violations "(ns clio.law.example)\n(def opts #js {:recursive true})\n"))))

  (testing "the js* special form"
    (is (= ["js* interop"]
           (violations "(ns clio.law.example)\n(defn boom [] (js* \"1 + 1\"))\n"))))

  (testing "a string host require in the ns form"
    (is (= ["host require \"node:fs\""]
           (violations "(ns clio.law.example (:require [\"node:fs\" :as fs]))\n")))))

(deftest host-interop-inside-a-reader-conditional-is-a-violation
  (testing "both branches of a .cljc conditional are inspected"
    (is (= ["js/ interop js/Date.now"]
           (violations
            (str "(ns clio.law.example)\n"
                 "(defn now [] #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))\n"))))))

(deftest host-interop-in-metadata-is-a-violation
  (testing "a js-namespaced tag hint is still host interop"
    (is (= ["js/ interop js/Date"]
           (violations "(ns clio.law.example)\n(defn f [^js/Date d] d)\n")))))

(deftest a-nested-js-literal-is-found
  (testing "walking descends into collections and tagged-literal payloads"
    (is (= ["#js literal"]
           (violations
            "(ns clio.law.example)\n(def config {:a [1 {:b #js {:deep true}}]})\n")))))

(deftest extern-js-namespaces-are-exempt-by-path
  (testing "on either separator, without consulting a host filesystem API"
    (is (boundary/extern-js-path? "/tmp/clio/src/clio/extern/js/fs.cljs"))
    (is (boundary/extern-js-path? "C:\\clio\\src\\clio\\extern\\js\\fs.cljs"))
    (is (not (boundary/extern-js-path? "/tmp/clio/src/clio/law/event.cljc")))))

(deftest only-clojure-source-extensions-are-scanned
  (is (boundary/source-path? "fs.cljs"))
  (is (boundary/source-path? "event.cljc"))
  (is (boundary/source-path? "clio.nbb"))
  (is (not (boundary/source-path? "clio.mjs")))
  (is (not (boundary/source-path? "README.md"))))

(deftest an-extern-path-is-exempt-from-file-violations
  (is (nil? (boundary/file-violations
             "/tmp/clio/src/clio/extern/js/fs.cljs"
             "(ns clio.extern.js.fs)\n(defn f [] (js/console.log \"x\"))\n")))
  (is (= ["js/ interop js/console.log"]
         (boundary/file-violations
          "/tmp/clio/src/clio/law/event.cljc"
          "(ns clio.law.event)\n(defn f [] (js/console.log \"x\"))\n"))))

(deftest quoted-symbols-are-data-not-interop
  (testing "a quoted js-namespaced symbol reaches no host object"
    (is (= [] (violations "(ns clio.law.example)\n(def marker 'js/console)\n"))))

  (testing "a quoted js* symbol is likewise data"
    (is (= [] (violations "(ns clio.law.example)\n(def marker 'foo/js*)\n"))))

  (testing "and so is a quoted collection of forms"
    (is (= [] (violations
               "(ns clio.law.example)\n(def forms '[(js/console.log 1) #js {:a 1}])\n")))))

(deftest quoting-does-not-launder-real-interop
  (testing "the same symbol unquoted is still a violation"
    (is (= ["js/ interop js/console"]
           (violations "(ns clio.law.example)\n(def marker js/console)\n"))))

  (testing "a quote elsewhere in the file does not disarm the rest of it"
    (is (= ["js/ interop js/console.log"]
           (violations
            (str "(ns clio.law.example)\n"
                 "(def marker 'js/console)\n"
                 "(defn f [] (js/console.log \"x\"))\n")))))

  (testing "but a syntax-quoted form is a known gap, pinned so it stays known"
    ;; edamame expands syntax-quote into concat/list machinery whose leaves are
    ;; ordinary (quote sym) forms, so this scanner cannot see the difference.
    ;; Asserted rather than wished away: if a future reader implementation
    ;; preserves syntax-quote, this test fails and the gap gets revisited.
    (is (= [] (violations "(ns clio.law.example)\n(def body `(js/console.log 1))\n")))))

(deftest metadata-on-a-quoted-form-is-still-inspected
  ;; The reader attaches a type hint to the quote form itself, so exempting
  ;; the payload must not exempt the node. Both rules hold on one node: the
  ;; hint is reported, the quoted symbol is not.
  (testing "a host type hint decorating a quoted form"
    (is (= ["js/ interop js/Date"]
           (violations "(ns clio.law.example)\n(def marker ^js/Date 'foo)\n"))))

  (testing "and the quoted payload under that hint stays exempt"
    (is (= ["js/ interop js/Date"]
           (violations "(ns clio.law.example)\n(def marker ^js/Date 'js/console)\n")))))
