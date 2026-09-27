# Desktop PatchDrop Autoconsume Design

> Status (2026-07-31): HOLD for implementation. A concurrent repository implementation under `scripts/desktop_patchdrop_auto_intake*` changed the same trust boundary after this design's evidence snapshot. Reconcile that implementation as the canonical path before using or extending the request/ready publisher described here; do not run two automatic intake protocols.

**Status:** System-selected design approved for implementation.

**EvidenceSnapshot:** `2d544829bcccadd8bcf16f925352d8b4cd48eabeb0a11e23bd7428ab78bc6e91`

## Goal

Allow a Notebook producer to publish a complete, hash-bound PatchDrop v3
handoff that a Desktop Codex startup preflight can discover and consume without
requiring a per-bundle command from the user.

## Selected Approach

Use a repository-owned Desktop Codex startup hook, not a Windows scheduled
task. The producer publishes an explicit autoconsume request and a ready marker
last. A Desktop-owned consumer validates the request, promotes exactly one
nested producer bundle, acquires a `desktop-consumer` source lease, applies the
manifest-pinned patch through the existing janitor, runs a fixed verification
profile, and records an idempotent result.

The hook is invoked when Desktop Codex or the Desktop control-tower starts a
repository task. It is not an always-running background process.

## Current Integration Constraint

`AGENTS.md` is the durable Desktop startup instruction surface, but it already
has an unowned dirty change in the current checkout. This implementation must
not modify it until Desktop or the current owner resolves that overlap.

The publisher, consumer, and contract tests are independent new files and may
be implemented first. Until the hook is installed, Desktop final activation is
`evidence_needed`.

## Components

### 1. Autoconsume Request Publisher

Create `scripts/publish_desktop_autoconsume_request.ps1`.

Inputs:

- `Root`: repository root containing `__patch_drop__`
- `Topic`: canonical lowercase PatchDrop topic
- `Node`: `notebook` or `macmini`
- `VerificationProfile`: one fixed allowlisted profile
- `RequestId`: optional caller-supplied lowercase identifier; generated when
  omitted
- `TtlMinutes`: 5 through 1440, default 180

The publisher verifies that the nested cumulative v3 patch, report, verify log,
SHA sidecar, manifest, and pending notice exist. It verifies the manifest
identity, `sourceIsolation.guard=PASS`,
`sourceIsolation.sourceRootKind=local-worktree`,
`sourceIsolation.directCanonicalSourceEdit=false`, and
`desktopFinalProof=evidence_needed`.

It writes:

```text
__patch_drop__/autoconsume/requests/<requestId>.json
__patch_drop__/autoconsume/requests/<requestId>.json.ready
```

The request file uses schema
`awx.patchdrop.autoconsume.request.v1`. The ready file uses schema
`awx.patchdrop.autoconsume.ready.v1` and binds the request filename and
SHA-256. Both files are written through same-directory temporary files. The
ready file is renamed last.

### 2. Desktop Autoconsume Consumer

Create `scripts/desktop_patchdrop_autoconsume.ps1`.

Modes:

- `Inspect`: read-only queue classification
- `Consume`: Desktop-only mutation after all gates pass

The consumer accepts an explicit `Root`. Production consumption requires the
Desktop canonical root. Tests may use another root only when it contains
`.awx-patchdrop-autoconsume-test-root`.

The consumer:

1. acquires a single autoconsume lock;
2. requires exactly one ready request;
3. validates the request and ready schemas, UTF-8, TTL, paths, and hashes;
4. rejects an existing result for the same request/hash as already processed;
5. rejects a changed request ID with a different hash;
6. requires zero top-level patches and zero source leases;
7. invokes the existing producer promotion helper;
8. acquires a `desktop-consumer` lease;
9. applies the exact promoted patch with `janitor_apply_one.ps1`;
10. executes the trusted verification profile;
11. releases the lease only after success or verified rollback;
12. records a redacted, hash-bound result.

### 3. Trusted Verification Profiles

The producer selects a profile name but cannot provide commands or arguments.
The Desktop consumer owns the command mapping.

| Profile | Commands |
| --- | --- |
| `documentation-only` | `git diff --check`, reverse-apply check |
| `powershell-tooling` | `git diff --check`, repository PowerShell contract tests named by Desktop policy, reverse-apply check |
| `java-focused` | sourceSet/LangChain4j guards, `compileJava -x test`, reverse-apply check |
| `java-cross-boundary` | sourceSet/LangChain4j guards, `compileJava -x test`, `:app:classes -x test`, reverse-apply check |

For the first implementation, `powershell-tooling` runs the autoconsume
publisher and consumer contract tests. Expanding the profile requires a
Desktop-owned source change and tests.

### 3.1 Desktop Task Preflight Entrypoint

Create `scripts/desktop_task_preflight.ps1` as the stable entrypoint used by
Desktop Codex instructions. It invokes the consumer once, propagates its exit
code and reason code, and performs no other source mutation. A missing or empty
autoconsume queue exits successfully with `queue-empty`; a HOLD or failure
stops the Desktop source-mutation phase for that task.

Keeping this entrypoint separate prevents future Desktop startup instructions
from duplicating consumer arguments or policy.

### 4. Result Ledger

Results live under:

```text
data/agent-handoff/patchdrop-autoconsume/<requestId>.json
```

Allowed states are:

- `inspected`
- `applied`
- `rolled-back`
- `hold`
- `rejected`

The ledger stores identifiers, safe relative filenames, hashes, counts, exit
codes, reason codes, and elapsed time. It never stores patch content, prompt
content, environment dumps, credentials, or raw command output.

## Safety and Failure Handling

- Multiple ready requests: `autoconsume-queue-ambiguous`
- Missing or malformed sidecar: `autoconsume-request-invalid`
- Expired request: `autoconsume-request-expired`
- Request/hash replay: return the previous result without mutation
- Top-level patch already present: `patch-drop-pending`
- Active lease: `source-lease-blocked`
- Index lock: `index-lock-conflict`
- Dirty target overlap: `dirty-overlap`
- Secret hit: `secret-leak-risk`
- Verification failure with successful reverse apply: `rolled-back`
- Verification failure with failed reverse apply: `rollback-required`;
  retain the lease and stop

No failure may fall through to the next request.

## Explicit Non-Goals

- Windows scheduled tasks, services, or always-running watchers
- automatic commits, pushes, pull requests, or deployments
- model downloads or `ollama pull`
- user- or machine-scope environment mutation
- DB, Supabase, credential, public API, or embedding mutation
- executing commands supplied by a producer manifest
- applying more than one request per Desktop task start

## Testing

Use self-contained PowerShell contract tests, not Pester, because the current
host exposes Pester 3.4.0 while unrelated plans require Pester 5.5+.

All mutation tests use a temporary fake Desktop root containing the explicit
test sentinel. Tests create real miniature producer bundles and invoke the real
publisher/consumer entry points. They assert exit codes, files, hashes, target
bytes, rollback, idempotency, and reason codes.

Minimum cases:

- ready marker is published last and binds the request hash;
- incomplete or non-isolated producer bundle is rejected;
- multiple ready requests fail closed;
- expired and tampered requests fail closed;
- replay is idempotent;
- producer commands cannot be injected;
- missing lease/apply helper fails closed;
- verification failure restores the preimage;
- rollback failure retains the lease;
- no test writes the real PatchDrop queue.

## Rollout

1. Implement and verify publisher and consumer against temp roots.
2. Keep real Desktop consumption disabled.
3. Resolve the existing `AGENTS.md` dirty overlap on Desktop.
4. Add one startup-preflight instruction that invokes
   `desktop_patchdrop_autoconsume.ps1 -Mode Consume`.
5. Run a Desktop temp-root end-to-end smoke.
6. Run one real, documentation-only bundle as the canary.

Desktop final activation remains `evidence_needed` until steps 3 through 6
produce Desktop-owned command evidence.
