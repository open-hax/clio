(ns clio.domain.schema
  (:require [clio.law.event :as event-law]
            [clio.law.schema :as schema-law]
            [clio.shape.canonical :as canonical]
            [clojure.string :as str]))

(defn- fail!
  [type message data]
  (throw (ex-info message (assoc data :clio/error type))))

(defn- empty-namespace-node
  [path]
  {:namespace/path path
   :namespace/schemas {}
   :namespace/children {}})

(defn- insert-schema
  [node path schema-name schema-form]
  (if (empty? path)
    (assoc-in node [:namespace/schemas schema-name] schema-form)
    (let [segment (first path)
          child-path (conj (:namespace/path node) segment)
          child (get-in node [:namespace/children segment]
                        (empty-namespace-node child-path))]
      (assoc-in node
                [:namespace/children segment]
                (insert-schema child (rest path) schema-name schema-form)))))

(defn catalog-tree
  "Build a namespace trie from a flat {qualified-schema-id -> Malli form}
   catalog. The trie is logical structure: source file boundaries never enter
   the hash."
  [catalog]
  (reduce-kv
   (fn [root schema-id schema-form]
     (when-not (and (keyword? schema-id) (namespace schema-id))
       (fail! :clio.schema/unqualified-id
              "Schema ids must be namespace-qualified keywords"
              {:schema/id schema-id}))
     (insert-schema root
                    (str/split (namespace schema-id) #"\.")
                    (name schema-id)
                    schema-form))
   (empty-namespace-node [])
   catalog))

(defn- schema-id
  [path schema-name]
  (keyword (str/join "." path) schema-name))

(def ^:private literal-position-types
  "Malli constructs whose children are literal values compared against, never
   sub-schemas — a qualified keyword appearing among them (e.g. the
   :flags/enabled in [:enum :flags/enabled] or [:not= :flags/enabled]) is data
   being matched, not a reference to a registered schema of that id. Every
   comparator belongs here, not only equality."
  #{:enum := :not= :> :>= :< :<= :const :fn :re})

(def ^:private labeled-entry-types
  "Malli constructs whose children are labeled entries rather than bare
   sub-schemas. The leading label is data — a :map key, an :orn/:catn/:altn
   branch name, a :multi dispatch value — so only an entry's trailing schema
   position is a schema position."
  #{:map :orn :catn :altn :multi})

(defn- schema-properties
  "The properties map of a Malli vector form, if it carries one. A properties
   map ({:title ...}, {:closed true}) is always distinguishable from a child
   schema this way: a bare map is never itself a valid Malli schema, in any
   position, for any construct — so whichever construct this is, if the first
   thing after its type keyword is a map, that map is properties."
  [node]
  (when (map? (second node))
    (second node)))

(defn- entry-schema-position
  "A labeled entry is `[label]`, `[label schema]`, or `[label opts schema]` —
   the schema, if present, is always the last element; `label` and `opts` are
   never schema positions. A two-element entry whose tail is a map carries
   options rather than a schema, because a bare map is never itself a valid
   Malli schema."
  [entry]
  (when (and (vector? entry) (> (count entry) 1))
    (let [tail (last entry)]
      (when-not (and (= 2 (count entry)) (map? tail))
        tail))))

(defn- schema-children
  "The children of a Malli vector form after its type keyword, with a leading
   properties map skipped — see schema-properties."
  [node]
  (let [children (rest node)]
    (if (and (seq children) (map? (first children)))
      (rest children)
      children)))

(defn- referenced-schema-ids
  "Every catalog schema id that schema-form points at directly, found by
   walking only positions Malli treats as sub-schemas. Malli represents a
   reference to another registered schema either explicitly as
   `[:ref :other/schema]` or as the bare qualified keyword itself resolved
   against the registry, so a qualified keyword present in the same catalog
   is treated as a reference — but only where a schema can appear, not where
   a literal value is compared (`:enum`/`:=`/`:not=`/comparators), not a
   labeled entry's own label, and not a properties map (`{:closed true}`).

   Three refinements follow Malli's own resolution rules rather than the shape
   of the data. A local `:registry` property is semantic, not presentation:
   its values are sub-schemas the form compiles against, so they are walked
   like children. Its keys also *shadow* the catalog for the subtree they
   scope, so a bare keyword resolved by a local definition is not a catalog
   dependency at all. And the head of a vector form is a schema position
   whenever it names a registered schema rather than a Malli construct:
   `[:counter/amount {:min 0}]` is that schema with properties applied."
  [catalog schema-form]
  (letfn [(local-registry [node]
            (when (sequential? node)
              (:registry (schema-properties node))))
          (walk [node shadowed]
            (cond
              (keyword? node)
              (if (and (contains? catalog node)
                       (not (contains? shadowed node)))
                #{node}
                #{})

              (and (sequential? node) (seq node))
              (let [registry (local-registry node)
                    scope (into shadowed (keys registry))
                    from-registry (into #{}
                                        (mapcat #(walk % scope))
                                        (vals registry))
                    head (first node)
                    ;; A head naming a registered schema is itself a schema
                    ;; position. It can never collide with a construct keyword:
                    ;; catalog-tree rejects unqualified ids, and every Malli
                    ;; construct keyword is unqualified.
                    from-head (walk head scope)]
                (cond
                  (contains? literal-position-types head)
                  from-registry

                  (contains? labeled-entry-types head)
                  (into (into from-registry from-head)
                        (mapcat (fn [entry]
                                  (when (vector? entry)
                                    (walk (entry-schema-position entry) scope))))
                        (schema-children node))

                  :else
                  (into (into from-registry from-head)
                        (mapcat #(walk % scope))
                        (schema-children node))))

              (map? node)
              (into #{} (mapcat #(walk % shadowed)) (mapcat identity node))

              :else #{}))]
    (walk schema-form #{})))

(defn- reference-closure
  "Every catalog schema id schema-id transitively depends on, excluding
   itself. A leaf hash over schema-form alone is blind to edits made to a
   schema it references by id — the form text is unchanged, but what it
   validates against has changed. Folding this closure's forms into the leaf
   preimage keeps the leaf hash sound under `:ref`/bare-keyword composition."
  [catalog schema-id]
  (loop [visited #{} pending (list schema-id)]
    (if-let [[id & more] (seq pending)]
      (if (contains? visited id)
        (recur visited more)
        (recur (conj visited id)
               (into more (referenced-schema-ids catalog (get catalog id)))))
      (disj visited schema-id))))

(declare merkleize-node)

(defn- merkleize-schemas
  [hash-fn catalog path schemas]
  (into
   (sorted-map)
   (map (fn [[schema-name schema-form]]
          (let [id (schema-id path schema-name)
                closure
                (->> (reference-closure catalog id)
                     sort
                     (mapv (fn [ref-id] [ref-id (get catalog ref-id)])))
                hash (hash-fn
                      (canonical/canonical-edn
                       [:schema id schema-form closure]))]
            [schema-name
             {:schema/id id
              :schema/form schema-form
              :merkle/hash hash}])))
   schemas))

(defn- merkleize-children
  [hash-fn catalog children]
  (into
   (sorted-map)
   (map (fn [[segment child]]
          [segment (merkleize-node hash-fn catalog child)]))
   children))

(defn merkleize-node
  [hash-fn catalog node]
  (let [path (:namespace/path node)
        schemas (merkleize-schemas hash-fn catalog path (:namespace/schemas node))
        children (merkleize-children hash-fn catalog (:namespace/children node))
        preimage
        [:namespace
         path
         (mapv (fn [[name schema]] [name (:merkle/hash schema)]) schemas)
         (mapv (fn [[segment child]] [segment (:merkle/hash child)]) children)]
        hash (hash-fn (canonical/canonical-edn preimage))]
    {:namespace/path path
     :namespace/schemas schemas
     :namespace/children children
     :merkle/hash hash}))

(defn- collect-schema-hashes
  [node]
  (let [local
        (into {}
              (map (fn [[_ schema]]
                     [(:schema/id schema) (:merkle/hash schema)]))
              (:namespace/schemas node))
        descendants
        (mapcat (comp seq collect-schema-hashes val)
                (:namespace/children node))]
    (into local descendants)))

(defn materialize
  "Derive an automatic schema revision. No manually maintained version number is
   involved: the root is a Merkle hash over namespace structure and schema data,
   while each schema also receives its own stable leaf hash."
  [hash-fn catalog]
  (let [tree (->> catalog catalog-tree (merkleize-node hash-fn catalog))]
    {:schema/root (:merkle/hash tree)
     :schema/catalog catalog
     :schema/hashes (collect-schema-hashes tree)
     :schema/tree tree}))

(defn schema-ref
  [revision schema-id]
  (let [schema-hash (get-in revision [:schema/hashes schema-id])]
    (when-not schema-hash
      (fail! :clio.schema/unknown-schema
             "Schema id is not present in revision"
             {:schema/id schema-id
              :schema/root (:schema/root revision)}))
    {:schema/root (:schema/root revision)
     :schema/id schema-id
     :schema/hash schema-hash}))

(defn revision-index
  [revisions]
  (reduce
   (fn [index revision]
     (let [root (:schema/root revision)]
       (if-let [old (get index root)]
         (if (= (:schema/catalog old) (:schema/catalog revision))
           index
           (fail! :clio.schema/root-collision
                  "Same schema root describes different catalogs"
                  {:schema/root root}))
         (assoc index root revision))))
   {}
   revisions))

(defn compatible-revisions
  "Return every known revision in which schema-id has exactly schema-hash. This
   lets an unchanged event shape remain valid across unrelated catalog changes
   without a manual version bump."
  [revisions schema-id schema-hash]
  (->> revisions
       (filter #(= schema-hash (get-in % [:schema/hashes schema-id])))
       (sort-by :schema/root)
       vec))

(defn resolve-event-schema
  "Resolve through the event's recorded whole-catalog root, then report every
   other known catalog revision carrying the exact same schema leaf."
  [revisions event]
  (let [{:schema/keys [root id hash]} (:event/schema event)
        by-root (revision-index revisions)
        revision (get by-root root)]
    (when-not revision
      (fail! :clio.schema/unknown-revision
             "Event references a schema revision that is not available"
             {:schema/root root :schema/id id :schema/hash hash}))
    (let [known-hash (get-in revision [:schema/hashes id])]
      (when-not known-hash
        (fail! :clio.schema/unknown-schema
               "Event references a schema absent from its catalog revision"
               {:schema/root root :schema/id id}))
      (when-not (= hash known-hash)
        (fail! :clio.schema/hash-mismatch
               "Event schema leaf hash does not match its catalog revision"
               {:schema/root root
                :schema/id id
                :event/schema-hash hash
                :known/schema-hash known-hash}))
      {:revision revision
       :compatible/revisions (compatible-revisions revisions id hash)
       :schema/id id
       :schema/form (get-in revision [:schema/catalog id])})))

(defn validate-event!
  "Resolve an event's historical contract, then apply the laws that decide
   whether this event is admissible."
  [revisions event]
  (schema-law/validate-bootstrap! event)
  (when-not (event-law/event-identity-valid? event)
    (fail! :clio.schema/invalid-identity
           "Event identity, stream sequence, or causal ids are invalid"
           {:event event}))
  (let [resolved (resolve-event-schema revisions event)
        schema-id (:schema/id resolved)
        revision (:revision resolved)]
    (when-not (= schema-id (:event/type event))
      (fail! :clio.schema/type-mismatch
             "Event type must equal its schema id"
             {:event/type (:event/type event)
              :schema/id schema-id}))
    (schema-law/validate-event-form!
     (:schema/catalog revision)
     schema-id
     event)
    ;; A Malli event schema can admit values canonical-form has no portable
    ;; encoding for (:uuid, :inst, :any, ...); catching that here means an
    ;; uncomputable event never reaches the ledger, instead of committing and
    ;; only discovering the problem the first time infra.projection hashes it.
    (try
      (canonical/canonical-form event)
      (catch #?(:clj Exception :cljs :default) cause
        (fail! :clio.schema/non-canonical-event
               "Event data contains a value with no portable canonical encoding"
               {:event event :cause (ex-message cause)}))))
  event)
