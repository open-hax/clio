(ns clio.shape.edn
  (:require [edamame.core :as edamame]))

(def ^:private edn-only-opts
  "edamame defaults to Clojure's full reader (fn literals, deref, var-quote,
   syntax-quote/unquote, read-eval, regex, ...). None of those are EDN, and a
   ledger/schema-snapshot file should only ever contain what `pr-str` on plain
   data produces, so disable the Clojure-only extensions edamame layers on
   top of `:all true` by default."
  {:all false})

(defn read-one
  "Read exactly one EDN form, enforcing true end-of-input and rejecting
   Clojure-only reader literals that are not part of EDN.

   `clojure.edn`/`cljs.reader` read-string stops at the end of the first form
   and never reports how much of the string it consumed, so wrapping the text
   in a synthetic `[ ... ]` to make a second form observable is unsound: a
   stray unescaped delimiter anywhere in malformed or tampered input can close
   that wrapper early, after which everything past it is silently unread and
   undetected. edamame tracks real reader position and balances delimiters
   across the whole input, so `parse-string-all` throws on any trailing
   content instead of quietly dropping it."
  [text]
  (let [forms (try
                (edamame/parse-string-all text edn-only-opts)
                (catch #?(:clj Exception :cljs :default) cause
                  (throw
                   (ex-info "Expected exactly one EDN form"
                            {:clio/error :clio.edn/expected-one-form
                             :cause (str cause)}))))]
    (when-not (= 1 (count forms))
      (throw
       (ex-info "Expected exactly one EDN form"
                {:clio/error :clio.edn/expected-one-form
                 :form-count (count forms)})))
    (first forms)))
