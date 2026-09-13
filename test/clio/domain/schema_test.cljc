(ns clio.domain.schema-test
  (:require [clio.domain.schema :as schema]
            [clio.law.schema :as schema-law]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])))

(defn fake-hash
  [text]
  (let [token (str (hash text) ":")]
    (apply str (take 64 (cycle token)))))

(def catalog-v1
  {:counter/amount
   :int

   :counter/added
   (schema-law/event-schema
    :counter/added
    [:map {:closed true}
     [:amount :counter/amount]])

   :counter/subtracted
   (schema-law/event-schema
    :counter/subtracted
    [:map {:closed true}
     [:amount :counter/amount]])})

(def catalog-v2
  {:counter/amount
   :int

   :counter/added
   (schema-law/event-schema
    :counter/added
    [:map {:closed true}
     [:amount :counter/amount]
     [:unit :keyword]])

   :counter/subtracted
   (schema-law/event-schema
    :counter/subtracted
    [:map {:closed true}
     [:amount :counter/amount]])})

(defn event
  [revision schema-id data]
  {:event/id "11111111-1111-4111-8111-111111111111"
   :event/schema (schema/schema-ref revision schema-id)
   :event/type schema-id
   :event/stream "counter:a"
   :event/seq 1
   :event/causes []
   :event/actor "user:alice"
   :event/subject "counter:a"
   :event/at "2026-08-09T00:00:00.000Z"
   :event/data data})

(defn error-code
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) cause
      (:clio/error (ex-data cause)))))

(deftest schema-version-is-derived-from-structure
  (let [v1 (schema/materialize fake-hash catalog-v1)
        v2 (schema/materialize fake-hash catalog-v2)]
    (testing "a changed schema changes the whole-catalog root"
      (is (not= (:schema/root v1) (:schema/root v2))))

    (testing "an unchanged schema keeps its leaf identity across roots"
      (is (= (get-in v1 [:schema/hashes :counter/subtracted])
             (get-in v2 [:schema/hashes :counter/subtracted]))))

    (testing "historical validation resolves qualified refs from that revision"
      (is (= (event v1 :counter/added {:amount 3})
             (schema/validate-event!
              [v1 v2]
              (event v1 :counter/added {:amount 3})))))

    (testing "the changed current shape is independently enforced"
      (is (= :clio.schema/invalid-event
             (error-code
              #(schema/validate-event!
                [v1 v2]
                (event v2 :counter/added {:amount 3}))))))

    (testing "an unchanged leaf reports compatibility across multiple known roots"
      (let [old (event v1 :counter/subtracted {:amount 2})
            resolved (schema/resolve-event-schema [v2 v1] old)]
        (is (= #{(:schema/root v1) (:schema/root v2)}
               (into #{} (map :schema/root) (:compatible/revisions resolved))))))

    (testing "leaf compatibility never fabricates provenance for an unknown root"
      (let [old (event v1 :counter/subtracted {:amount 2})
            moved (assoc-in old [:event/schema :schema/root]
                            (apply str (repeat 64 "f")))]
        (is (= :clio.schema/unknown-revision
               (error-code #(schema/validate-event! [v1 v2] moved))))))

    (testing "a known root cannot lie about its schema leaf"
      (let [old (event v1 :counter/subtracted {:amount 2})
            tampered (assoc-in old [:event/schema :schema/hash]
                               (apply str (repeat 64 "e")))]
        (is (= :clio.schema/hash-mismatch
               (error-code #(schema/validate-event! [v1 v2] tampered))))))))

(deftest leaf-hash-tracks-transitively-referenced-schemas
  ;; :counter/added's own form is byte-identical across these two catalogs; only
  ;; the schema it references by bare qualified keyword (:counter/amount)
  ;; changes. A leaf hash computed from schema-form alone would be blind to
  ;; that and wrongly report the two revisions as carrying the same contract.
  (let [referenced-v1 {:counter/amount :int
                        :counter/added
                        (schema-law/event-schema
                         :counter/added
                         [:map {:closed true} [:amount :counter/amount]])}
        referenced-v2 {:counter/amount [:and :int [:>= 0]]
                       :counter/added
                       (schema-law/event-schema
                        :counter/added
                        [:map {:closed true} [:amount :counter/amount]])}
        v1 (schema/materialize fake-hash referenced-v1)
        v2 (schema/materialize fake-hash referenced-v2)]
    (is (= (get-in referenced-v1 [:counter/added])
           (get-in referenced-v2 [:counter/added]))
        "sanity check: the referencing schema's own form did not change")
    (is (not= (get-in v1 [:schema/hashes :counter/added])
              (get-in v2 [:schema/hashes :counter/added]))
        "the referencING schema's leaf hash must move when what it references does")))

(deftest leaf-hash-ignores-literal-values-that-look-like-schema-ids
  ;; :flags/enabled below is a literal value compared for equality, not a
  ;; reference to a schema of that id, even though the catalog happens to
  ;; define one. Changing the unrelated :flags/enabled schema must not move
  ;; :counter/added's leaf hash.
  (let [base {:flags/enabled :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true} [:amount [:enum :flags/enabled]]])}
        changed {:flags/enabled [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true} [:amount [:enum :flags/enabled]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (= (get-in v1 [:schema/hashes :counter/added])
           (get-in v2 [:schema/hashes :counter/added]))
        "a literal keyword matching a catalog schema id is not a reference")))

(deftest leaf-hash-ignores-map-options-that-look-like-schema-ids
  ;; :flags/enabled sits in a :map entry's OPTIONS map ({:title ...}) here,
  ;; never a schema position, even though the catalog happens to define a
  ;; schema of that id. Changing the unrelated :flags/enabled schema must not
  ;; move :counter/added's leaf hash.
  (let [base {:flags/enabled :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amount {:title :flags/enabled} :int]])}
        changed {:flags/enabled [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amount {:title :flags/enabled} :int]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (= (get-in v1 [:schema/hashes :counter/added])
           (get-in v2 [:schema/hashes :counter/added]))
        "a keyword in a :map entry's options map is not a reference")))

(deftest leaf-hash-ignores-non-map-properties-that-look-like-schema-ids
  ;; :flags/enabled sits in :vector's own properties map here (not a :map
  ;; entry), never a schema position. Any Malli construct can carry a leading
  ;; properties map — a bare map is never itself a valid schema position for
  ;; any construct, so this generalizes beyond :map specifically.
  (let [base {:flags/enabled :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amounts [:vector {:title :flags/enabled} :int]]])}
        changed {:flags/enabled [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amounts [:vector {:title :flags/enabled} :int]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (= (get-in v1 [:schema/hashes :counter/added])
           (get-in v2 [:schema/hashes :counter/added]))
        "a keyword in any construct's properties map is not a reference")))

(deftest leaf-hash-follows-local-registry-references
  ;; Malli's :registry option is semantic: the local definition's value is a
  ;; sub-schema the form compiles against, so changing the catalog entry it
  ;; points at must change the event schema's leaf hash even though the rest
  ;; of the properties map is not walked.
  (let [base {:shared/value :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amount [:schema {:registry {:local/value :shared/value}}
                          :local/value]]])}
        changed {:shared/value [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amount [:schema {:registry {:local/value :shared/value}}
                             :local/value]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (not= (get-in v1 [:schema/hashes :counter/added])
              (get-in v2 [:schema/hashes :counter/added]))
        "a catalog id referenced through a local :registry is a dependency")))

(deftest leaf-hash-ignores-catalog-ids-shadowed-by-a-local-registry
  ;; Malli resolves :local/value against the form's own :registry, so the
  ;; catalog entry of the same name is not part of the effective validator.
  ;; Changing that shadowed catalog entry must not move the leaf hash.
  (let [base {:local/value :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amount [:schema {:registry {:local/value :int}}
                          :local/value]]])}
        changed {:local/value [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amount [:schema {:registry {:local/value :int}}
                             :local/value]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (= (get-in v1 [:schema/hashes :counter/added])
           (get-in v2 [:schema/hashes :counter/added]))
        "a catalog id shadowed by a local :registry definition is not a reference")))

(deftest leaf-hash-follows-catalog-ids-outside-a-local-registry-scope
  ;; A local registry shadows only the subtree it scopes. The same keyword
  ;; used as a sibling of that subtree still resolves against the catalog.
  (let [base {:local/value :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amount [:tuple
                          [:schema {:registry {:local/value :int}} :local/value]
                          :local/value]]])}
        changed {:local/value [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amount [:tuple
                             [:schema {:registry {:local/value :int}} :local/value]
                             :local/value]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (not= (get-in v1 [:schema/hashes :counter/added])
              (get-in v2 [:schema/hashes :counter/added]))
        "local registry shadowing is lexically scoped to the form that declares it")))

(deftest leaf-hash-ignores-comparator-literals-that-look-like-schema-ids
  ;; :not= compares its child against a literal value, exactly as := does.
  (let [base {:flags/enabled :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true} [:amount [:not= :flags/enabled]]])}
        changed {:flags/enabled [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true} [:amount [:not= :flags/enabled]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (= (get-in v1 [:schema/hashes :counter/added])
           (get-in v2 [:schema/hashes :counter/added]))
        "a :not= literal keyword is not a reference")))

(deftest leaf-hash-ignores-branch-labels-that-look-like-schema-ids
  ;; An :orn branch label is data naming the branch, not a schema position,
  ;; even when the catalog happens to define a schema of that id.
  (let [base {:flags/enabled :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amount [:orn [:flags/enabled :int] [:absent :nil]]]])}
        changed {:flags/enabled [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amount [:orn [:flags/enabled :int] [:absent :nil]]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (= (get-in v1 [:schema/hashes :counter/added])
           (get-in v2 [:schema/hashes :counter/added]))
        "an :orn branch label is not a reference")))

(deftest leaf-hash-follows-branch-children-under-literal-looking-labels
  ;; The inverse hazard of the label rule: a branch labeled :enum must not be
  ;; mistaken for an [:enum ...] literal construct, which would silently drop
  ;; the branch's real child and make an INCOMPATIBLE revision look compatible.
  (let [base {:shared/value :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amount [:orn [:enum :shared/value] [:absent :nil]]]])}
        changed {:shared/value [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amount [:orn [:enum :shared/value] [:absent :nil]]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (not= (get-in v1 [:schema/hashes :counter/added])
              (get-in v2 [:schema/hashes :counter/added]))
        "a labeled branch's child schema is still a reference")))

(deftest leaf-hash-follows-multi-branch-children
  ;; :multi entries are labeled by dispatch value; only the trailing schema
  ;; position is a schema.
  (let [base {:flags/enabled :boolean
              :shared/value :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amount [:multi {:dispatch :kind}
                          [:flags/enabled [:map [:v :shared/value]]]]]])}
        changed {:flags/enabled :boolean
                 :shared/value [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amount [:multi {:dispatch :kind}
                             [:flags/enabled [:map [:v :shared/value]]]]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (not= (get-in v1 [:schema/hashes :counter/added])
              (get-in v2 [:schema/hashes :counter/added]))
        "a :multi branch's child schema is a reference even when its dispatch value is not")))

(deftest leaf-hash-follows-head-position-schema-references
  ;; Malli's vector syntax is [type properties & children], and a registered
  ;; schema id is a legal type: [:counter/amount {:min 0}] is that schema with
  ;; properties applied. The head is therefore a schema position whenever it
  ;; names a catalog entry rather than a Malli construct.
  (let [base {:counter/amount :int
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amount [:counter/amount {:min 0}]]])}
        changed {:counter/amount [:int {:max 10}]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amount [:counter/amount {:min 0}]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (not= (get-in v1 [:schema/hashes :counter/added])
              (get-in v2 [:schema/hashes :counter/added]))
        "a catalog id in head position is a reference")))

(deftest leaf-hash-ignores-head-position-ids-shadowed-by-a-local-registry
  ;; Head position is a schema position, so it obeys the same local-registry
  ;; scope every other schema position does.
  (let [base {:local/value :boolean
              :counter/added
              (schema-law/event-schema
               :counter/added
               [:map {:closed true}
                [:amount [:schema {:registry {:local/value :int}}
                          [:local/value {:min 0}]]]])}
        changed {:local/value [:enum true false]
                 :counter/added
                 (schema-law/event-schema
                  :counter/added
                  [:map {:closed true}
                   [:amount [:schema {:registry {:local/value :int}}
                             [:local/value {:min 0}]]]])}
        v1 (schema/materialize fake-hash base)
        v2 (schema/materialize fake-hash changed)]
    (is (= (get-in v1 [:schema/hashes :counter/added])
           (get-in v2 [:schema/hashes :counter/added]))
        "a shadowed catalog id in head position is not a reference")))
