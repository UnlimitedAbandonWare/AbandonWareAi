---
name: scoped-blocker-recovery
description: Use when repeated HOLD/no_progress, index.lock, or partial GPU blocks demo-1 work
---

# Scoped Blocker Recovery

Separate permission to perform a specific operation from acceptance of the original
goal. Preserve the original objective and failure evidence; record the changed
dependency decision in the current task.

Read [decision-table.md](references/decision-table.md) for operation and GPU cases.
Read [lease-lifecycle.md](references/lease-lifecycle.md) for file/prefix reservations,
supervising-process identity, heartbeat, automatic quarantine and ownership logs.
Use [review-prompts.md](references/review-prompts.md) only when the existing
three-query preflight requires review; it is the same review, not an extra panel.

1. Read current instructions and exact targets. Resolve Project Root from
   `AGENTS.md` (`DEMO1-PROJECT-ROOT` / `DEMO1-GIT-LOCAL-FIRST`), then inspect
   active source ownership, pending PatchDrop, leases, live file hashes, and
   actual writers. Do not require `git status`/`rev-parse`, a `.git` directory,
   or a clean index for ordinary worktree-edit. Freeze target hashes and the
   intended candidate. Historical HOLD is an observation, not a permanent
   dependency or a requirement to remove a lock.
2. Classify the operation as read-only, worktree-edit, index-write, build, or runtime.
   Use the existing `__patch_drop__/source_edit_lease_contract.ps1` functions
   `Get-AwxGitOperationEvidence` and `Get-AwxScopedOperationDecision`. A positive
   decision supplies no missing authorization. Builds require known side effects
   and isolated caches; runtime work requires the relevant endpoint/model/function.
3. For source edits, retain the existing three-query preflight and source-owner
   workflow. Supply a JSON `TargetManifest` with `targets: [{path, sha256}]`;
   `sha256: null` explicitly means an authorized new path. Run the existing
   `source_edit_session.ps1 -Action begin -TargetManifest <file>` with the actual
   root, role, topic and owner. Immediately before application run `-Action verify`
   with that manifest and the SHA-256 of the acquired `lease.json` as
   `-LeaseFingerprint`. Recheck candidate applicability, then apply only those hunks.
   Compare postimages and task-only diff, preserve user hunks, run active tests.
   Target manifests are the default for concurrent Desktop work. The existing
   registry compares normalized target paths, allowing disjoint sessions and
   disjoint pending patches. `status -TargetManifest` is scoped; the legacy
   unscoped status is inventory, not a repository-wide stop. If an older active
   lease stores only `targetManifestHash`, `-Action bind-scope` accepts the exact
   hash-pinned original manifest without rewriting the lease or its fingerprint.
4. If the old guard blocks its own authorized correction, reproduce the overblock
   in an isolated Git fixture first. Patch the smallest existing guard after real
   writer/lease/preimage safety is proven. Do not wait for unrelated GPU repair or
   delete an unknown lock merely to satisfy the old blanket rule.
5. Use `goal_next.ps1 -IndependentWorkAvailable` when independent work remains.
   Its existing auto entrypoint saves a stable blocker fingerprint and skips an
   unchanged full audit without refreshing success timestamps. After recovery,
   pass `-RecoveryEvidencePath <redacted-proof>`; changed source, resource, request
   or proof identity permits reassessment. Repeating a command must change a
   decision and stay within attempt/time limits. A repeat count never completes
   or blocks the whole Codex goal by itself.
6. End the owned lease with its owner and `-LeaseFingerprint`. Unknown resources,
   changed identities, shared-root violations and overlapping writers remain held.
   No guard bypass flags, force-kill, reset, firewall change, provider substitution,
   protected configuration or runtime-memory write is authorized here.

Validate with `python -B -m unittest discover -s scripts -p test_scoped_blocker_recovery.py`
and affected existing lease/goal/harness tests. Use
[scenarios.json](evals/scenarios.json) for behavioral evaluation; fixtures are not
live acceptance. Report applied paths, active-code hashes, test counts, held scope,
next deciding observation, and `not_observed` for missing inference/fallback proof.
Skill validation and explicit invocation do not prove automatic discovery in a new session.
