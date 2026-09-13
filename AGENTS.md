# Clio — Agent Guidance

Clio is a content-addressed event sourcing kernel for ClojureScript and NBB.

## Quick commands

```bash
# Tests (all three runtimes)
pnpm test

# Individual test suites
pnpm test:bb    # Babashka
pnpm test:nbb   # NBB
pnpm test:shadow # shadow-cljs

# Lint
pnpm lint       # clj-kondo + extern boundary check
pnpm lint:kondo # clj-kondo only

# Clean
pnpm clean
```

## Architecture

Namespaces follow the layer construction order:

| Layer | Pattern | Rule |
|---|---|---|
| `law.*` | Contracts/Malli | No I/O. Validators only. |
| `shape.*` | Data morphisms | Pure, domain-agnostic. |
| `extern.*` | JS/Node boundaries | Only layer touching raw host objects. |
| `domain.*` | Business logic | No I/O. Pure functions only. |
| `infra.*` | Orchestration | Composes extern + domain. |

Only `clio.extern.js.*` namespaces may touch Node/JS directly. Enforced by `scripts/lint_extern_boundary.bb`.

## Dependencies

- Maven: malli, edamame, promesa
- npm: fs-ext-extra-prebuilt, nbb
- No workspace or sibling dependencies

## License

LGPL-3.0-or-later
