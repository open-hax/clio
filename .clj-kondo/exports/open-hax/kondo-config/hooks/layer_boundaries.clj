(ns hooks.layer-boundaries
  "Construction-order gate for the `law → shape → extern → domain → infra`
   namespace architecture (AGENTS.md § Clojure Construction Order).

   Each layer may require the layers below it and its own siblings, and nothing
   above:

     law    — no dependencies. Validators only.
     shape  — law. Pure, domain-agnostic morphisms.
     extern — law, shape. The only place raw JS/Node/browser/SDK boundaries live.
     domain — law, shape. Pure decisions over shaped data.
     infra  — everything. Effect orchestration.

   A host require (a string libspec such as \"node:fs/promises\", \"chokidar\",
   or \"fastify\") is therefore legal only in `extern.*` and `infra.*`.

   Two finding types, so a package can tune them apart:

     :layer-boundary/upward-require — a require pointing up the construction order
     :layer-boundary/host-require   — a host module required from a pure layer

   The layer of a namespace is its last segment named after a layer, so
   `rheos.backend.domain.task-create` is `domain` and
   `open-hax.sol.extern.fetch` is `extern`. A namespace with no layer segment is
   not checked, and neither is a `*-test` one.

   `cljs.core/ns` takes exactly one analyze-call hook, so this one also runs the
   promise-chain ns check that used to be wired there directly."
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]
            [hooks.promise-chain :as promise-chain]))

(def ^:private layers
  #{"law" "shape" "extern" "domain" "infra"})

(def ^:private allowed-below
  {"law"    #{"law"}
   "shape"  #{"law" "shape"}
   "extern" #{"law" "shape" "extern"}
   "domain" #{"law" "shape" "domain"}
   "infra"  #{"law" "shape" "extern" "domain" "infra"}})

(def ^:private host-layers
  #{"extern" "infra"})

(def ^:private depends-on
  {"law"    "nothing — a law is a description, so it has no dependencies"
   "shape"  "law and its own layer"
   "extern" "law, shape, and its own layer"
   "domain" "law, shape, and its own layer"
   "infra"  "every layer below it"})

(defn- layer-of
  "The layer a namespace belongs to: its last layer-named segment, or nil."
  [ns-sym]
  (when ns-sym
    (some layers (reverse (str/split (str ns-sym) #"\.")))))

(defn- test-ns? [ns-sym]
  (str/ends-with? (str ns-sym) "-test"))

(defn- require-clause?
  "Is `node` a `(:require ...)` or `(:require-macros ...)` clause?"
  [node]
  (and (api/list-node? node)
       (let [head (first (:children node))]
         (and head
              (api/keyword-node? head)
              (contains? #{:require :require-macros} (api/sexpr head))))))

(defn- libspec-nodes
  "The node naming the library in each libspec of a require clause: the symbol or
   string itself for a bare libspec, the first element for a vector one."
  [clause]
  (keep (fn [node]
          (cond
            (api/vector-node? node) (first (:children node))
            (api/token-node? node) node
            (api/string-node? node) node
            :else nil))
        (rest (:children clause))))

(defn- finding [node type message]
  (assoc (meta node) :type type :message message))

(defn- libspec-finding
  "The boundary this libspec crosses, if any. `lib` is already an sexpr."
  [layer node lib]
  (if (string? lib)
    (when-not (host-layers layer)
      (finding node :layer-boundary/host-require
               (str "a " layer " namespace must not require the host: \"" lib "\". "
                    "Decode foreign data in extern.*, orchestrate effects in infra.*.")))
    (when-let [dep-layer (layer-of lib)]
      (when-not (contains? (allowed-below layer #{}) dep-layer)
        (finding node :layer-boundary/upward-require
                 (str "a " layer " namespace must not require " lib " (" dep-layer "): "
                      layer " depends on " (depends-on layer) "."))))))

(defn- layer-findings [node]
  (let [children (:children node)
        ns-sym (some-> (second children) api/sexpr)
        layer (when (and (symbol? ns-sym) (not (test-ns? ns-sym)))
                (layer-of ns-sym))]
    (when layer
      (for [clause (filter require-clause? children)
            lib-node (libspec-nodes clause)
            :let [lib (api/sexpr lib-node)]
            :when (or (string? lib) (symbol? lib))
            :let [f (libspec-finding layer lib-node lib)]
            :when f]
        f))))

(defn check-ns [{:keys [node] :as ctx}]
  (doseq [f (try (layer-findings node)
                 (catch Exception _ nil))]
    (api/reg-finding! f))
  (promise-chain/check-ns ctx))

(defn check-js-star
  "Flag every `js*` special-form call. `:config-in-ns` turns this off for the
   namespaces a package designates as its extern.js.* boundary, so raw JS
   interop stays reported everywhere else without this hook needing to know
   which namespace it is currently analyzing."
  [{:keys [node]}]
  (api/reg-finding!
   (finding node :layer-boundary/js-star-interop
            "raw `js*` interop must stay behind extern.js.*; decode foreign data there and return Clojure values."))
  {:node node})
