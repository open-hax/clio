# Durable retry acknowledgments

An append can write visible bytes and then fail at `fsync` / `FileChannel.force`.
Reading those bytes again proves visibility, not crash durability. An exact
retry now validates the event and flushes the same descriptor that still owns
the kernel lock before returning `:already-present`.

`clio.infra.ledger/ensure-durable!` accepts schema revisions and an existing
ledger path. It locks the existing inode, validates every recorded event,
flushes that owning descriptor, and releases it even on failure. Local service
providers call this boundary before returning a successful no-change decision
from a projection. It neither appends duplicate facts nor creates missing files.

Recovered sandbox verification on 2026-09-12:

- A real Node filesystem failure observer reproduced three failures against
  the old retry branch: a rejected append stayed visible, its retry succeeded
  without a flush, and recovery never called fsync again.
- After correction: NBB 63 tests / 158 assertions; JVM 65 / 182; Babashka
  25 / 75; compiled Shadow 63 / 158, 115 files, zero compiler warnings.
- Configured Clio lint: zero errors and warnings; native boundary check clean.
  One pre-existing informational excluded-var diagnostic remains informational.
- The JVM test injects failure at the named actual channel-force boundary and
  verifies append, exact retry, and projection fence all refuse until forcing
  succeeds. Both hosts retain one event after successful retry.

These tests establish syscall ordering and failure propagation. They do not
simulate physical power loss or promise durability on filesystems whose native
flush/lock operations reject the requested contract. No earlier lost checkout's
test totals are inherited by this recovered source.
