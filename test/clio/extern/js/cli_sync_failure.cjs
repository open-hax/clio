// Failure injection at the real published CLI's native fsync boundary.
const fs = require("node:fs");
const path = require("node:path");
const { syncBuiltinESMExports } = require("node:module");
const ledger = process.env.CLIO_CLI_SYNC_PATH;
const phase = process.env.CLIO_CLI_SYNC_PHASE;
const trace = process.env.CLIO_CLI_SYNC_TRACE;
const open = fs.openSync;
const sync = fs.fsyncSync;
const handles = new Map();
let ledgerSeen = false;
fs.openSync = function (...args) {
  const fd = open.apply(this, args);
  handles.set(fd, String(args[0]));
  return fd;
};
fs.fsyncSync = function (fd) {
  const file = handles.get(fd);
  if (file === ledger) ledgerSeen = true;
  const fence = file === ledger ? "inode" :
    ledgerSeen && file === path.dirname(ledger) ? "parent" : null;
  if (fence && trace) fs.appendFileSync(trace, `${fence}\n`);
  if (fence === phase) throw new Error(`Injected CLI ${phase} synchronization failure`);
  return sync.call(this, fd);
};
syncBuiltinESMExports();
