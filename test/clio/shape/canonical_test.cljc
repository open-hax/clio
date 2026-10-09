(ns clio.shape.canonical-test
  (:require [clio.shape.canonical :as canonical]
            [clio.shape.edn :as edn]
            #?(:clj [clio.extern.jvm.crypto :as crypto]
               :cljs [clio.extern.js.crypto :as crypto])
            #?(:cljs [clio.extern.js.canonical-fixture :as fixture]
               :clj [clio.extern.jvm.test-support :as fixture])
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

(deftest identifiers-must-round-trip-through-the-persisted-edn
  (doseq [value [(keyword "bad key") (keyword "bad\nkey")
                 (keyword "bad]key") (keyword "")
                 (keyword "bad namespace" "key")
                 (keyword nil "a/b")
                 (symbol "bad key") (symbol "bad]key")
                 (symbol "") (symbol "nil") (symbol "true")
                 (symbol "12") (symbol "bad namespace" "key")
                 (symbol nil "a/b")]]
    (is (= :clio.canonical/invalid-identifier
           (error-code #(canonical/canonical-edn {:nested [value]})))
        (pr-str value)))
  (doseq [value [:plain :namespace/name :with-hyphen :with.dot
                 (keyword "λ") (symbol "/") 'namespace/name 'with-hyphen '+]]
    (is (= [(if (keyword? value) :keyword :symbol) (namespace value) (name value)]
           (canonical/canonical-form value)))
    (is (= value (edn/read-one (pr-str value))))))

(deftest standard-edn-tags-have-portable-preimages
  (is (= "[:uuid \"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\"]"
         (canonical/canonical-edn #uuid "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")))
  (is (= "[:inst [:number :safe-integer \"0\"]]"
         (canonical/canonical-edn #inst "1970-01-01T00:00:00.000Z")))
  (is (= "[:inst [:number :safe-integer \"-1\"]]"
         (canonical/canonical-edn #inst "1969-12-31T23:59:59.999Z")))
  (is (= (canonical/canonical-edn #inst "2026-09-11T00:00:00.001Z")
         (canonical/canonical-edn #inst "2026-09-11T02:00:00.001+02:00")))
  (is (not= (canonical/canonical-edn #uuid "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
            (canonical/canonical-edn "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")))
  (is (not= (canonical/canonical-edn #inst "1970-01-01T00:00:00.000Z")
            (canonical/canonical-edn 0))))

(deftest strings-must-contain-only-unicode-scalars
  (doseq [value ["\uD800" "\uDBFF" "\uDC00" "\uDFFF" "\uDC00\uD800"
                "\uD800x" "x\uDFFF" "\uD800\uD800\uDC00" "\uD800\uDC00\uDC00"]
          container [value {:nested [value]} {value true} #{value} (list value)]]
    (is (= :clio.canonical/invalid-unicode
           (error-code #(canonical/canonical-edn container)))
        "Malformed UTF-16 must be refused before a host can replace it during UTF-8 hashing"))
  (doseq [value ["" "plain text" "λ中" "?" "\uFFFD" "\uD7FF" "\uE000"
                "\uD800\uDC00" "\uDBFF\uDFFF" "x\uD834\uDD1E\uD83D\uDE42y"
                "e\u0301" "é"]]
    (is (= [:string value] (canonical/canonical-form value))
        "Every valid scalar string retains its exact preimage without normalization"))
  (is (not= (canonical/canonical-edn "e\u0301") (canonical/canonical-edn "é"))))

(deftest identifier-names-and-namespaces-must-contain-only-unicode-scalars
  (doseq [part ["\uD800" "\uDC00"]
          identifier [(keyword part) (keyword "valid" part) (keyword part "valid")
                      (symbol part) (symbol "valid" part) (symbol part "valid")]]
    (is (= :clio.canonical/invalid-unicode
           (error-code #(canonical/canonical-edn identifier)))
        "Readable identifiers cannot smuggle malformed Unicode around string validation"))
  (doseq [identifier [(keyword "音楽" "\uD834\uDD1E") (symbol "音楽" "\uD834\uDD1E")]]
    (is (= [(if (keyword? identifier) :keyword :symbol) (namespace identifier) (name identifier)]
           (canonical/canonical-form identifier)))))

(deftest valid-unicode-preimages-have-the-same-native-utf8-hashes
  ;; Observed independently through the real JVM and Node UTF-8 SHA-256 adapters
  ;; before the refusal guard: supplementary scalars must keep these identities.
  (doseq [[value digest] [["λ中\uD834\uDD1E\uD83D\uDE42" "5d1013f9a77c3425771a66901a4cb16685a1f43ab8b1bf5d62f73da0b5da8f84"]
                          ["\uD800\uDC00" "bf1e636a4aec0c6818a43e4685bc53326635235c9fc55f7981aa619c0fb2cf9d"]
                          ["\uDBFF\uDFFF" "f2deaa3ee94aa278d9466ce172c97b618fd3837e89e9e13176fe4c61e88ef611"]]]
    (is (= digest (crypto/sha256 (canonical/canonical-edn value))))))

#?(:cljs
   (deftest invalid-dates-and-host-objects-are-refused
     (is (= :clio.canonical/invalid-instant
            (error-code #(canonical/canonical-edn (fixture/invalid-instant)))))
     (is (= :clio.canonical/unsupported-value
            (error-code #(canonical/canonical-edn (fixture/arbitrary-object)))))))

(deftest nonportable-numbers-are-refused
  (is (= :clio.canonical/non-portable-number
         (error-code #(canonical/canonical-edn {:too-big 9007199254740992}))))
  (is (= :clio.canonical/non-portable-number
         (error-code #(canonical/canonical-edn {:nan ##NaN}))))
  (is (= :clio.canonical/non-portable-number
         (error-code #(canonical/canonical-edn {:inf ##Inf})))))

(deftest only-round-trippable-gregorian-instants-are-admitted
  (doseq [millis [-12219292800000 -1 0 253402300799999]]
    (let [value (fixture/instant-at millis)
          reread (edn/read-one (pr-str value))]
      (is (= millis (inst-ms reread)))
      (is (= (canonical/canonical-edn value) (canonical/canonical-edn reread)))))
  (doseq [millis [-12219292800001 -14831769600000 253402300800000 8640000000000000]]
    (is (= :clio.canonical/invalid-instant
           (error-code #(canonical/canonical-edn (fixture/instant-at millis)))))))

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
