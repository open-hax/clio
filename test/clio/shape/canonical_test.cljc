(ns clio.shape.canonical-test
  (:require [clio.shape.canonical :as canonical]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])))

(defn error-code
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) cause
      (:clio/error (ex-data cause)))))

(deftest maps-and-sets-ignore-iteration-order
  (is (= (canonical/canonical-edn {:b #{3 2} :a 1})
         (canonical/canonical-edn {:a 1 :b #{2 3}}))))

(deftest equal-sequential-collections-share-one-preimage
  (is (= (canonical/canonical-edn [1 2 3])
         (canonical/canonical-edn '(1 2 3)))))

(deftest nonportable-numbers-are-refused
  (is (= :clio.canonical/non-portable-number
         (error-code #(canonical/canonical-edn {:too-big 9007199254740992}))))
  (is (= :clio.canonical/non-portable-number
         (error-code #(canonical/canonical-edn {:nan ##NaN}))))
  (is (= :clio.canonical/non-portable-number
         (error-code #(canonical/canonical-edn {:inf ##Inf})))))

#?(:clj
   (deftest jvm-only-exact-reals-are-refused
     (let [huge (reduce *' 1 (repeat 400 10))
           huge-ratio (/ huge 3)
           tiny-ratio (/ 1 huge)]
       ;; Overflow used to coerce to +Inf and make real-decomposition loop
       ;; forever; underflow used to collapse a nonzero ratio onto zero.
       (is (= :clio.canonical/non-portable-number
              (error-code #(canonical/canonical-edn huge-ratio))))
       (is (= :clio.canonical/non-portable-number
              (error-code #(canonical/canonical-edn tiny-ratio))))
       ;; JVM Ratio/BigDecimal semantics do not exist in JavaScript. Reject the
       ;; family instead of deciding portability by a coercive numeric equality
       ;; test that can erase exact type/value distinctions.
       (is (= :clio.canonical/non-portable-number
              (error-code #(canonical/canonical-edn (/ 1 3)))))
       (is (= :clio.canonical/non-portable-number
              (error-code #(canonical/canonical-edn (/ 1 2)))))
       (is (= :clio.canonical/non-portable-number
              (error-code #(canonical/canonical-edn (bigdec "0.5"))))))))

(deftest finite-real-numbers-are-portable
  ;; A valid event schema can permit :double data; canonical-edn must not
  ;; reject an ordinary floating-point value committed as event payload.
  (is (some? (canonical/canonical-edn {:amount 1.5})))
  (is (not= (canonical/canonical-edn {:amount 1.5})
            (canonical/canonical-edn {:amount 1}))))

(deftest number-encodings-are-runtime-independent
  ;; These literals are pinned exactly: they were computed identically on the
  ;; JVM (bb) and on JavaScript (nbb), where host printing would otherwise
  ;; decide the form — (str 1.0) is "1.0" on the JVM but "1" in ClojureScript,
  ;; and 1e-7 prints "1.0E-7" vs "1e-7".
  (is (= "[:number :real 1 \"6755399441055744\" -52]"
         (canonical/canonical-edn 1.5)))
  (is (= "[:number :real 1 \"7205759403792794\" -56]"
         (canonical/canonical-edn 0.1)))
  (is (= "[:number :real 1 \"7555786372591432\" -76]"
         (canonical/canonical-edn 1e-7)))
  (is (= "[:number :real -1 \"6192449487634432\" -51]"
         (canonical/canonical-edn -2.75)))
  ;; An integer-valued double denotes an integer on both runtimes; -0.0
  ;; canonicalizes to +0.0 since (= -0.0 0.0) holds everywhere.
  (is (= "[:number :safe-integer \"1\"]"
         (canonical/canonical-edn 1.0)))
  (is (= "[:number :safe-integer \"0\"]"
         (canonical/canonical-edn -0.0)))
  (is (= "[:number :safe-integer \"9007199254740991\"]"
         (canonical/canonical-edn 9007199254740991.0)))
  ;; 1e20 is integer-valued on ClojureScript but a Double on the JVM; both
  ;; runtimes must refuse it rather than disagreeing.
  (is (= :clio.canonical/non-portable-number
         (error-code #(canonical/canonical-edn {:too-big 1e20})))))