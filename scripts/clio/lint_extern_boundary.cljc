(ns clio.lint-extern-boundary
  "Refuse raw host interop outside `clio.extern.js.*`.

   The boundary is a property of the *forms* a file contains, not of its text:
   a `js/` sequence inside a docstring, comment, or string literal performs no
   interop and must not fail the package lint. Every check here therefore runs
   over parsed source.

   The policy is runtime-neutral and carries no filesystem access, so the same
   rules are verified under Babashka, NBB, and Shadow CLJS. Walking a directory
   tree and choosing an exit code belong to the host entrypoint,
   `scripts/lint_extern_boundary.bb`."
  (:require [clojure.string :as str]
            [edamame.core :as edamame]))

(defn source-path?
  [path]
  (boolean (re-find #"\.(?:clj|cljc|cljs|nbb)$" path)))

(defn extern-js-path?
  "Is this path inside the one namespace family allowed to touch the host?
   Windows separators are normalized rather than consulted, so the predicate
   stays free of any host filesystem API."
  [path]
  (str/includes? (str/replace path "\\" "/") "/extern/js/"))

(defn- without-shebang
  "A .nbb entrypoint starts with a `#!/usr/bin/env nbb` line, which the Clojure
   reader cannot parse — drop it before reading the ns form."
  [text]
  (if (str/starts-with? text "#!")
    (if-let [idx (str/index-of text "\n")] (subs text idx) "")
    text))

(def parse-options
  "Read source as data rather than text. Reader conditionals are preserved so
   both branches of a `.cljc` form are inspected, and every unknown tag —
   `#js` included — survives as a tagged literal instead of being evaluated."
  {:all true
   :read-cond :preserve
   :features #{:clj :cljs}
   :auto-resolve (fn [alias] (if (= :current alias) "clio" (name alias)))
   :readers (fn [tag] (fn [value] (tagged-literal tag value)))})

(defn parse-forms
  [text]
  (edamame/parse-string-all (without-shebang text) parse-options))

(defn js-marker
  "The boundary violation this single node is, if any. Only reader-level
   positions count: a symbol in the `js` namespace, the `js*` special form, or
   a `#js` literal."
  [node]
  (cond
    (symbol? node) (cond
                     (= "js" (namespace node)) (str "js/ interop " (pr-str node))
                     (= "js*" (name node)) "js* interop"
                     :else nil)
    (tagged-literal? node) (when (= 'js (:tag node)) "#js literal")
    :else nil))

(defn- quoted?
  "Is this form `(quote x)` — a symbol or structure held as data?

   Known gap: syntax-quote is covered by this too, and not by choice. edamame
   expands a syntax-quoted form into `concat`/`list` machinery whose leaves are
   ordinary `(quote sym)` forms, indistinguishable from a hand-written quote,
   so a macro emitting `js/console.log` is not reported here. The clj-kondo
   `:layer-boundary/js-star-interop` hook still covers `js*`, and Clio defines
   no macros; closing the rest would mean re-reading source as text, which is
   the failure this scanner exists to avoid."
  [form]
  (and (seq? form) (= 'quote (first form)) (= 2 (count form))))

(defn js-markers
  "Every boundary violation reachable from form, walking collections, metadata,
   tagged-literal payloads, and both branches of preserved reader conditionals.

   Quoted payloads are skipped: a quoted symbol is data and reaches no host
   object, so flagging it would fail valid source for the same reason scanning
   comments and strings did. Metadata is examined first and unconditionally:
   the reader attaches a type hint to the quote form itself, and a hint is host
   interop whatever it decorates. The exemption is to skip the payload, not to
   skip the node."
  [form]
  (concat
   (when-let [m (meta form)] (js-markers m))
   (when-not (quoted? form)
     (concat
      (when-let [marker (js-marker form)] [marker])
      (cond
        (tagged-literal? form) (js-markers (:form form))
        ;; A preserved reader conditional is an opaque ReaderConditional on the
        ;; JVM and needs naming; on ClojureScript edamame yields a map-like value
        ;; whose :form the map branch below already reaches.
        #?@(:clj [(reader-conditional? form) (js-markers (:form form))])
        (map? form) (mapcat js-markers (mapcat identity form))
        (coll? form) (mapcat js-markers form)
        :else nil)))))

(defn require-clauses
  [ns-form]
  (when (and (seq? ns-form) (= 'ns (first ns-form)))
    (filter #(and (seq? %) (contains? #{:require :require-macros} (first %)))
            (drop 2 ns-form))))

(defn host-requires
  [ns-form]
  (for [clause (require-clauses ns-form)
        libspec (rest clause)
        :let [lib (cond
                    (string? libspec) libspec
                    (vector? libspec) (first libspec)
                    :else nil)]
        :when (string? lib)]
    lib))

(defn source-violations
  "Every boundary violation in already-read source text."
  [text]
  (let [forms (parse-forms text)]
    (concat
     (map #(str "host require " (pr-str %)) (host-requires (first forms)))
     (distinct (mapcat js-markers forms)))))

(defn file-violations
  "Boundary violations for one file, given its path and contents. Files inside
   the extern.js boundary are exempt by construction."
  [path text]
  (when-not (extern-js-path? path)
    (source-violations text)))
