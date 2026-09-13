#!/usr/bin/env node

// Published executable for `@eta-mu/clio`.
//
// `bin/clio.nbb` cannot be the npm bin target directly. npm installs a bin as a
// symlink under the consumer's `node_modules/.bin`, and nbb discovers `nbb.edn`
// — and therefore this package's `src` classpath — by walking up from the
// script path it is handed, without resolving symlinks. Invoked through the
// link it would search the consumer's tree and find nothing. Its
// `#!/usr/bin/env nbb` line has the same shape of problem: it resolves whatever
// nbb happens to be on PATH, not the version this package declares.
//
// So this launcher resolves both from the installed package itself: the real
// path of `bin/clio.nbb`, and the `nbb` in this package's own dependencies. The
// child inherits the caller's working directory, so ledger and catalog
// arguments stay relative to where the user actually is.

import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

const binDir = path.dirname(fileURLToPath(import.meta.url));
const entrypoint = path.join(binDir, "clio.nbb");
const nbb = createRequire(import.meta.url).resolve("nbb/cli.js");

const child = spawn(process.execPath, [nbb, entrypoint, ...process.argv.slice(2)], {
  stdio: "inherit",
});

child.on("exit", (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
  } else {
    process.exit(code ?? 0);
  }
});
