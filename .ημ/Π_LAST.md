# Temporary checkout backup

This snapshot preserves source commit `4cde249f740d519c028bf7659304d07c3e79dc0a` from `refs/heads/codex/antigravity-review` before retiring an idle scratch checkout. The working tree and index were clean, including the ignored-file check. The parent commit carries the complete source tree. Only the handoff manifests were added.

Verification checked the existing remote source ref, clean status and the manifest hashes. No product test run was needed for the manifest-only change.
