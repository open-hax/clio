// Pause one real CLI read before the owning descriptor is acquired. All I/O stays native.
const fs = require('node:fs');
const path = require('node:path');
const target = process.env.CLIO_CLI_RACE_PATH;
if (target) {
  const open = fs.openSync;
  fs.openSync = function(file, ...args) {
    if (typeof file === 'string' && path.resolve(file) === target) {
      fs.openSync = open;
      fs.writeFileSync(process.env.CLIO_CLI_RACE_READY, 'ready');
      const deadline = Date.now() + 20_000;
      const pause = new Int32Array(new SharedArrayBuffer(4));
      while (!fs.existsSync(process.env.CLIO_CLI_RACE_RELEASE)) {
        if (Date.now() > deadline) throw new Error('CLI reader barrier was not released');
        Atomics.wait(pause, 0, 0, 5);
      }
    }
    return open.call(fs, file, ...args);
  };
  require('node:module').syncBuiltinESMExports();
}
