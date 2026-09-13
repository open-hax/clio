# Clio

Clio is a small content-addressed event-sourcing kernel for eta-mu.

It treats the immutable event set plus its causal/order graph as authority. A
physical ledger file is only one storage partition of that graph, and a
projection is disposable derived state.

## Laws

1. Committed events are immutable facts.
2. `:event/id` is globally stable identity. Exact duplicate ids dedupe; the same
   id with different data is corruption.
3. `:event/causes` defines causal edges. Every named cause must be present when a
   complete history is canonicalized. Cause ids are unique and an event cannot
   cause itself.
4. `[:event/stream :event/seq]` defines an order-sensitive stream slot. Two
   distinct events claiming the same slot are a concurrent-write conflict.
5. Each stream revision after `1` directly names the previous stream event as a
   cause. This first slice intentionally canonicalizes complete stream histories,
   so sequences are contiguous from `1`.
6. Physical file order and file partitioning have no semantic authority.
7. A projection is a pure fold over the canonical event order and can be deleted
   and reconstructed.

For the same event multiset `E`, arbitrary physical partitions must therefore
obey:

```text
canonicalize(partition-a(E)) = canonicalize(partition-b(E))
project(canonicalize(partition-a(E))) = project(canonicalize(partition-b(E)))
```

Concurrent events in different streams remain unordered by the causal DAG. Clio
uses `[stream-id, stream-seq, event-id]` only as a deterministic replay tie-break
for graph-incomparable events; domain code must not mistake that tie-break for a
causal claim.

## Event shape

There is deliberately no hand-maintained `:event/version` integer.

```clojure
{:event/id "11111111-1111-4111-8111-111111111111"
 :event/schema
 {:schema/root "<sha256 of the complete namespace/schema tree>"
  :schema/id :post/published
  :schema/hash "<sha256 of this schema leaf>"}
 :event/type :post/published
 :event/stream "post:p1"
 :event/seq 1
 :event/causes []
 :event/actor "user:alice"
 :event/subject "post:p1"
 :event/at "2026-08-09T00:00:00.000Z"
 :event/data {:post/id "p1" :text "hello"}}
```

The schema bootstrap is intentionally small: it reveals the schema root, schema
id, leaf hash, and event type. It is a fixed protocol foothold, **not** an
addressable event schema. Once the bootstrap is readable, the exact historical
Malli event schema validates the complete envelope. Because `event-schema`
emits that complete envelope into every catalog leaf, envelope changes also
change event-schema identity automatically.

## Content-derived schema revisions

An event-schema catalog is plain Clojure data mapping qualified schema ids to
Malli forms. `clio.law.schema/event-schema` is a Clojure function you call
while *authoring* that map in code — the CLI's `catalog-file` argument is read
as plain EDN, never evaluated, so a `catalog.edn` file cannot itself contain a
function call. Build the catalog in Clojure/ClojureScript:

```clojure
{:post/published
 (clio.law.schema/event-schema
  :post/published
  [:map {:closed true}
   [:post/id :string]
   [:text :string]])}
```

then serialize the *evaluated* result — the map `event-schema` returns, with
the full envelope already expanded — as what `catalog.edn` actually contains:

```clojure
{:post/published
 [:map {:closed true}
  [:event/id [:string {:min 36 :max 36}]]
  [:event/schema [:map {:closed true}
                  [:schema/root [:string {:min 64 :max 64}]]
                  [:schema/id :keyword]
                  [:schema/hash [:string {:min 64 :max 64}]]]]
  [:event/type [:= :post/published]]
  [:event/stream [:string {:min 1}]]
  [:event/seq [:int {:min 1}]]
  [:event/causes [:vector [:string {:min 36 :max 36}]]]
  [:event/actor [:string {:min 1}]]
  [:event/subject [:string {:min 1}]]
  [:event/at [:string {:min 1}]]
  [:event/data [:map {:closed true} [:post/id :string] [:text :string]]]]}
```

Clio derives a Merkle tree from the **logical namespace hierarchy and schema
forms**, never from source filenames:

```text
root
└── post
    ├── published  -> hash([:schema :post/published <malli-form>])
    └── retracted  -> hash([:schema :post/retracted <malli-form>])
```

Each namespace node hashes its namespace path, schema-leaf hashes, and child-node
hashes. The root hash therefore identifies the complete catalog structure.

When source schemas change, `clio.infra.runtime/open` materializes the new root
and persists the full catalog snapshot under that root automatically. There is no
version constant to remember to bump. Historical snapshots stay readable.

A leaf has its own hash as well as the catalog root. If `:post/retracted` is
unchanged while some unrelated `:dm/message-sent` schema changes, the two catalog
roots differ but the post schema leaf hash remains identical. Clio can therefore
report that an event shape is valid in every **known** catalog revision carrying
that same leaf hash while still requiring the event's recorded source root to be
known and internally consistent. A familiar leaf hash never fabricates an
unknown source revision.

Schemas participating in revision identity must be data-only Malli forms. A
function, JS object, or other runtime identity in a schema is rejected by the
canonical EDN encoder rather than producing an unstable hash.

### Hash-protocol boundary

Schema edits version themselves; the hash protocol cannot recursively version
itself. This first protocol is deliberately fixed to canonical-EDN preimages plus
SHA-256. Changing the canonical encoding or digest algorithm is therefore a
**protocol migration**, not an ordinary schema edit, and must retain a reader for
roots produced by the previous protocol. That boundary is explicit rather than
pretending a Merkle root can identify the rules used to compute itself without a
stable bootstrap convention.

## Namespace construction order

Reusable code follows the repository construction order:

```text
law -> shape -> extern -> domain -> infra
```

- `clio.law.*` — runtime-neutral predicates and invariants, including the
  ledger admission contract (`.cljc`).
- `clio.shape.*` — runtime-neutral Malli/canonical data shapes (`.cljc`).
- `clio.domain.*` — runtime-neutral schema Merkle logic, canonicalization, and
  projection folds (`.cljc`).
- `clio.extern.js.*` — the only Clio namespace family that touches Node/JS
  libraries or builtins. Functions accept Clojure data and return Clojure data;
  Node handles and JS option objects stay inside the boundary (`.cljs`).
- `clio.infra.*` — NBB/Node orchestration over the pure kernel and boundary
  functions (`.cljs`).
- `bin/*.nbb` and `test/*.nbb` — thin NBB executable entrypoints only.
- `bin/clio.mjs` — the published npm executable. It is a launcher, not logic:
  see [Consuming from npm](#consuming-from-npm).

The shared clj-kondo construction-order hook recognizes `extern`, and Clio
promotes boundary findings to errors. A Clio-specific boundary lint
additionally refuses raw `js/`, `#js`, `js*`, or string host requires in
production source outside `clio.extern.js.*`.

That boundary lint reads *forms*, not text: it parses every source file with
edamame — preserving both branches of a reader conditional and keeping `#js`
as a tagged literal — and reports only reader-level positions. A `js/` sequence
inside a comment, docstring, or string literal performs no interop and is not a
finding, and neither is a quoted symbol: `(def marker 'js/console)` is data,
not host access. (Syntax-quote rides along with that exemption, because
edamame expands it into `quote` leaves this scanner cannot tell apart from a
hand-written one — a documented gap, pinned by a test.) The rules are
runtime-neutral and carry no filesystem access, so they
live in `scripts/clio/lint_extern_boundary.cljc` and the package suite verifies
them on the same three runtimes as the kernel. Directory traversal and the exit
code stay in the Babashka entrypoint, `scripts/lint_extern_boundary.bb`, beside
this repository's other build gates.

Reusable runtime-specific code is `.cljs`, rather than `.nbb`, so the same
namespace implementation can be loaded by NBB and compiled by Shadow CLJS. The
`.nbb` files contain executable wiring, not a second implementation.

## Ledger format

A ledger is newline-delimited EDN:

```clojure
{:event/id "..." ...}
{:event/id "..." ...}
```

This keeps appends genuinely append-only. A complete logical history can be the
union of any number of such ledgers. Exact duplicated events across files are
harmless.

`append-event!` serializes its complete read → validate → collision-check →
append critical section with an **OS-backed advisory lock on the ledger inode
itself**. Every Clio writer participates in this lock protocol; external writers
must take the same advisory lock if they mutate the same ledger. The same locked
file descriptor is used for the read and append. On Unix the authoritative lock
is a blocking whole-file POSIX `fcntl(F_SETLKW)` write lock — advisory, not the
unrelated and effectively dead POSIX *mandatory locking* feature; `flock` is an
additional local-filesystem guard when supported. On Windows the adapter uses
an exclusive `LockFileEx` range covering the file address space. Closing the
descriptor — including process exit or crash — releases the kernel lock, so
Clio has no stale lockfile, lease timeout, PID-reclamation protocol, or
application-level fencing race. Symlink and hard-link aliases therefore contend
on the same underlying file identity rather than on path-derived lock names.

Whether a candidate event may join a partition is a law, not transport.
`clio.law.ledger/append-admission` classifies it against the events already
present — `:appendable`, `:already-present`, `:id-collision`, or
`:stream-slot-conflict` — and `clio.infra.ledger` only turns that verdict into
an error or a write. Laws 2 and 4 above are the contract it restates, so a
second ledger transport inherits them instead of reimplementing them.

`append-event!` never brings a ledger into being. The lock open carries no
`O_CREAT`, and the path is checked by name first, so a misspelled, deleted, or
unmounted ledger fails with `:clio.ledger/missing-file` instead of quietly
becoming a fresh empty history while the intended ledger stays behind.
`create-ledger!` — the CLI's `new` — is the only creation path.

A POSIX record lock is released when the process closes *any* descriptor for
that file, not only the one that took the lock. Path-based `read-text` would
therefore drop a held lock silently, so `clio.extern.js.fs` tracks the paths
this process has locked and refuses that read outright; callers inside a
critical section use `read-locked-text`. The guard keys on the path while the
lock keys on the inode, so a hard-link alias under another name is not caught.

`clio.domain.canonicalize/canonicalize` performs:

```text
ledger files
    |
    v
union -> historical-schema validation -> UUID dedupe
    -> missing-parent checks
    -> stream-slot / continuity checks
    -> causal DAG
    -> deterministic topological order
```

The returned DAG preserves parent/child relationships separately from the replay
vector so callers do not have to infer causality from vector adjacency.

## CLI

Installed from npm, the entrypoint is `clio`; inside this repository it is
`nbb bin/clio.nbb`. Every command below is shown in the installed form.

Create an append-only ledger:

```bash
npx clio new events.edn
```

Inspect a catalog's automatically derived revision:

```bash
npx clio schema-root catalog.edn
```

Append an event using a catalog and persistent schema-history directory:

```bash
npx clio append \
  .clio/schemas catalog.edn events-a.edn :counter/opened \
  '{:event/stream "counter:a"
    :event/seq 1
    :event/actor "user:alice"
    :event/subject "counter:a"
    :event/data {:amount 10}}'
```

Canonicalize arbitrarily partitioned ledgers:

```bash
npx clio canonicalize \
  .clio/schemas events-a.edn events-b.edn events-c.edn
```

## Consuming from npm

The published tarball is source plus the descriptors needed to build and run
it — `src`, `bin`, `nbb.edn`, `deps.edn`, and this README.

**As a CLI.** `npm install @eta-mu/clio` exposes `clio`. The manifest's bin
target is `bin/clio.mjs`, a Node launcher rather than `bin/clio.nbb` directly,
because npm installs a bin as a symlink in the consumer's `node_modules/.bin`
and nbb discovers `nbb.edn` — and therefore this package's classpath — by
walking up from the script path it is given, without resolving symlinks. The
launcher hands nbb the real entrypoint path and the `nbb` this package depends
on, and inherits the caller's working directory so ledger and catalog arguments
stay relative to the user. Resolving the Malli dependency on first run needs a
JVM available, as it does for any nbb project.

**As a ClojureScript library.** Add the installed `src` tree to your source
paths. `deps.edn` in the package root records the Maven libraries that source
actually requires — the same versions this repository builds and tests against:

| Dependency | Version |
| --- | --- |
| `metosin/malli` | 0.16.4 |
| `borkdude/edamame` | 1.6.42 |
| `funcool/promesa` | 11.0.678 |

Under Shadow CLJS, add them to `:dependencies`; under NBB, note that edamame and
promesa are bundled with nbb, so only Malli needs declaring, as `nbb.edn` shows.

## Verification

```bash
pnpm --dir packages/clio lint
pnpm --dir packages/clio test
```

The kernel suite runs under both NBB and Shadow CLJS. A third Babashka runner
(`test/run.bb`) pins what only a JVM runtime can: that canonical encoding is
byte-identical across hosts. The boundary-lint rules are runtime-neutral and run
under all three. Its
partition-invariance
fixture exhaustively checks all 24 permutations of four causally related events
across all `3^4 = 81` assignments to three physical ledgers: 1,944 distinct
physical layouts must converge to the same canonical event-id vector and the same
projection. Separate cases cover duplicated physical copies, schema-root/leaf
version behavior, historical validation, UUID collisions, missing parents,
competing stream revisions, causal cycles, append retry idempotence, and atomic
projection replacement.

The locking tests include real child processes and hard-link aliases: one test
proves a contender blocks while another process holds the inode lock; another
starts colliding appenders through different names for the same inode and proves
that only one stream-slot write commits.

PR-time verification runs in `main-pr-gate.yml`'s `clio` job; there is no
package-local CI receipt file.

## License

GNU LGPL v3 or later.
