// Keep inherited output pipes open after the group leader exits.
const fs = require('node:fs');
const {spawn} = require('node:child_process');
if (process.argv[2] === 'child') {
  const leader = Number(process.argv[3]), ready = process.argv[4];
  const timer = setInterval(() => {
    if (process.ppid !== leader) {
      fs.writeFileSync(ready, 'leader exited; child owns pipes');
      clearInterval(timer);
    }
  }, 5);
  // A final backstop prevents an orphan even if the harness's group cleanup regresses.
  setTimeout(() => process.exit(2), 40_000);
} else {
  const child = spawn(process.execPath, [__filename, 'child', String(process.pid), process.argv[2]], {stdio: 'inherit'});
  child.once('spawn', () => process.exit(0));
}
