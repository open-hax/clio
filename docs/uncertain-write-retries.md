# Durable retry acknowledgments

An append can write visible bytes and then fail at `fsync` / `FileChannel.force`.
Reading those bytes again proves visibility, not crash durability. An exact
retry now validates the event and flushes the same descriptor that still owns
the kernel lock before returning `:already-present`.

`clio.infra.ledger/ensure-durable!` accepts schema revisions and an existing
ledger path. It locks the existing inode, validates every recorded event,
flushes that owning descriptor and then its parent directory, and releases it even on failure. Local service
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

## Creation and reopen follow-up

Codex finding 3996093507 identified a separate uncertainty window: exclusive
creation can leave a visible empty ledger after either the inode or parent
flush fails. Existence alone cannot acknowledge that creation on a later open.
Validated protocols and Sol openers now call the same `ensure-durable!` boundary;
append and exact retry also force the inode followed by its parent directory.
Recovery preserves the existing inode and its facts rather than deleting a
file another opener may already be using.

Actual fault injection failed first with seven Node assertions, six JVM
assertions, and two public JavaScript reopen cases. The final kernel passes
Babashka 25 tests / 75 assertions, JVM 66 / 198, and NBB and compiled Shadow
64 / 174. Shadow compiled 115 files with zero warnings; configured lint and the
native boundary gate passed. The pre-existing excluded-var message remains
informational. These observations prove the requested syscall order and refusal
paths, including retries after both creation failure phases; physical power loss
was not simulated.

The final protocols consumer additionally passes 71 CLJS tests / 217 assertions
and 13 native Node tests without skips, including actual competing first
openers, typed consumers, and the creation/reopen failures. CodeRabbit finding
3996101377 also led to a failure-first sequence-shape guard for translation
batches; invalid inputs preserve ledger bytes. Test and library compilation,
strict TypeScript, and kondo complete with zero warnings.
