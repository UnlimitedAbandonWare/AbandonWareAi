# Desktop PatchDrop Autoconsume Implementation Plan

> Status (2026-07-31): HOLD after Task 1. The source guard detected a concurrent `desktop_patchdrop_auto_intake*` implementation in the watched boundary. Tasks 2–6 must not proceed until Desktop selects one canonical intake path and resolves the active lease/dirty overlap.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fail-closed Notebook request publisher and Desktop Codex task-start consumer that automatically processes one verified PatchDrop v3 handoff without a per-bundle user command.

**Architecture:** A shared PowerShell module owns schemas, hashing, canonical-path validation, atomic publication, queue classification, idempotency, and trusted verification profiles. Thin publisher, consumer, and Desktop task-preflight scripts call that module and reuse the existing producer-promotion, source-lease, and janitor-apply helpers. A root `AGENTS.md` instruction activates the preflight only after its current dirty overlap is resolved.

**Tech Stack:** Windows PowerShell 5.1+, self-contained PowerShell contract tests, PatchDrop v3 JSON/SHA sidecars, Git apply/reverse-apply, existing Desktop source-edit lease and janitor scripts.

## Global Constraints

- Canonical Notebook workspace remains `Y:\`; never print its backing UNC path.
- Desktop final source owner remains `C:\AbandonWare\demo-1\demo-1\src`.
- Production consumption is permitted only from that Desktop root.
- A noncanonical root is accepted only when it contains `.awx-patchdrop-autoconsume-test-root`.
- Process at most one ready request per invocation.
- Require exactly one cumulative, manifest-pinned PatchDrop v3 bundle.
- Reuse `janitor_promote_producer_pending.ps1`, `source_edit_session.ps1`, and `janitor_apply_one.ps1`; do not duplicate their validation or lease protocols.
- Producers may select only `documentation-only`, `powershell-tooling`, `java-focused`, or `java-cross-boundary`; they may not provide commands or arguments.
- Do not execute model downloads, environment mutation, DB mutation, credential mutation, commits, pushes, deployments, or producer-supplied commands.
- Do not modify `AGENTS.md` while its current dirty change has no proven owner.
- All mutation tests use temporary test roots and must prove the real PatchDrop queue is untouched.
- Do not require Pester; use repository-style self-contained PowerShell assertions.
- Every public result is redacted and contains only safe identifiers, relative paths, hashes, counts, exit codes, elapsed time, and reason codes.
- Desktop final activation remains `desktopFinalProof=evidence_needed` until a Desktop-owned canary succeeds.

---

## File Structure

- Create `scripts/modules/PatchDropAutoconsume.psm1`: schemas, validation, publication, intake, idempotency, trusted verification, rollback, and result helpers.
- Create `scripts/publish_desktop_autoconsume_request.ps1`: Notebook/Mac mini request-publication entrypoint.
- Create `scripts/desktop_patchdrop_autoconsume.ps1`: Desktop Inspect/Consume entrypoint.
- Create `scripts/desktop_task_preflight.ps1`: stable Desktop Codex startup entrypoint.
- Create `scripts/desktop_patchdrop_autoconsume_contract_tests.ps1`: temp-root behavioral tests for all three scripts.
- Modify `AGENTS.md` only after dirty ownership is resolved: add one Desktop task-start preflight rule.

## Shared Contracts

Request JSON:

```json
{
  "schemaVersion": "awx.patchdrop.autoconsume.request.v1",
  "requestId": "notebook-ollama-tooling-20260731-a1b2c3d4",
  "topic": "ollama-tooling",
  "node": "notebook",
  "verificationProfile": "powershell-tooling",
  "createdAtUtc": "2026-07-31T00:00:00.0000000Z",
  "expiresAtUtc": "2026-07-31T03:00:00.0000000Z",
  "nestedPatch": "notebook/ollama-tooling-notebook-v3.patch",
  "nestedManifest": "notebook/ollama-tooling-notebook-v3.manifest.json",
  "nestedManifestSha256": "64-lowercase-hex",
  "desktopFinalProof": "evidence_needed"
}
```

Ready JSON:

```json
{
  "schemaVersion": "awx.patchdrop.autoconsume.ready.v1",
  "requestFile": "notebook-ollama-tooling-20260731-a1b2c3d4.json",
  "requestSha256": "64-lowercase-hex"
}
```

Result JSON:

```json
{
  "schemaVersion": "awx.patchdrop.autoconsume.result.v1",
  "requestId": "notebook-ollama-tooling-20260731-a1b2c3d4",
  "requestSha256": "64-lowercase-hex",
  "topic": "ollama-tooling",
  "node": "notebook",
  "mode": "Consume",
  "state": "applied",
  "reason": "verified",
  "verificationProfile": "powershell-tooling",
  "exitCode": 0,
  "elapsedMs": 1000,
  "desktopFinalProof": "PASS"
}
```

---

### Task 1: Shared Contract Module and Atomic Request Publisher

**Files:**
- Create: `scripts/modules/PatchDropAutoconsume.psm1`
- Create: `scripts/publish_desktop_autoconsume_request.ps1`
- Create: `scripts/desktop_patchdrop_autoconsume_contract_tests.ps1`

**Interfaces:**
- Produces: `Get-AwxSha256Hex([byte[]]) -> string`
- Produces: `Test-AwxCanonicalName([string]) -> bool`
- Produces: `Write-AwxAtomicUtf8([string] path, [string] text) -> void`
- Produces: `Get-AwxProducerBundle([string] root, [string] topic, [string] node) -> PSCustomObject`
- Produces: `Publish-AwxAutoconsumeRequest(...) -> PSCustomObject`

- [ ] **Step 1: Write the failing publisher contract tests**

Create a test harness with literal assertions:

```powershell
$ErrorActionPreference = 'Stop'
$script:Failures = 0
function Assert-True([string]$Name, [bool]$Condition, [string]$Detail) {
    if (-not $Condition) {
        $script:Failures++
        Write-Host "[FAIL] $Name $Detail"
    } else {
        Write-Host "[PASS] $Name"
    }
}
```

The tests must build a complete miniature nested Notebook v3 bundle in a temp
root and assert:

- incomplete sidecars exit nonzero with `producer-bundle-missing`;
- a manifest without `sourceIsolation.guard=PASS` is rejected;
- an unsupported verification profile is rejected;
- publication creates one request and one ready file;
- the ready file hash equals the real request SHA-256;
- the ready timestamp is not earlier than the request timestamp;
- the request contains no absolute root or environment values.

- [ ] **Step 2: Run RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_autoconsume_contract_tests.ps1 -Suite Publisher
```

Expected: nonzero exit with `publisher-entrypoint-missing` because the module
and publisher do not exist.

- [ ] **Step 3: Implement the minimal module publication functions**

Use strict UTF-8 without BOM, lowercase SHA-256, canonical topic/request
patterns, same-directory temporary files, flush, re-read hash validation, and
rename. Validate these exact manifest values:

```powershell
$manifest.schemaVersion -ceq 'patchdrop-producer-v3'
$manifest.desktopFinalProof -ceq 'evidence_needed'
$manifest.sourceIsolation.guard -ceq 'PASS'
$manifest.sourceIsolation.sourceRootKind -ceq 'local-worktree'
$manifest.sourceIsolation.directCanonicalSourceEdit -is [bool]
$manifest.sourceIsolation.directCanonicalSourceEdit -eq $false
```

Write the request first and the ready JSON last. Never overwrite an existing
request ID unless its request hash is identical.

- [ ] **Step 4: Implement the thin publisher entrypoint**

```powershell
[CmdletBinding()]
param(
    [string]$Root = '.',
    [Parameter(Mandatory = $true)][string]$Topic,
    [ValidateSet('notebook','macmini')][string]$Node = 'notebook',
    [ValidateSet('documentation-only','powershell-tooling','java-focused','java-cross-boundary')]
    [string]$VerificationProfile = 'powershell-tooling',
    [string]$RequestId = '',
    [ValidateRange(5,1440)][int]$TtlMinutes = 180
)
```

Import the module by a path relative to `$PSScriptRoot`, invoke
`Publish-AwxAutoconsumeRequest`, emit one compact JSON result, and return
nonzero for every rejected publication.

- [ ] **Step 5: Run GREEN**

Run the Publisher suite again. Expected: every Publisher assertion passes,
`secretPatternHits=0`, and exit code 0.

- [ ] **Step 6: Record checkpoint without committing**

Suggested commit message only:

```text
feat: publish hash-bound PatchDrop autoconsume requests
```

---

### Task 2: Read-Only Desktop Intake and Idempotency

**Files:**
- Modify: `scripts/modules/PatchDropAutoconsume.psm1`
- Create: `scripts/desktop_patchdrop_autoconsume.ps1`
- Modify: `scripts/desktop_patchdrop_autoconsume_contract_tests.ps1`

**Interfaces:**
- Consumes: Task 1 request/ready schemas
- Produces: `Get-AwxAutoconsumeQueue([string] root) -> PSCustomObject`
- Produces: `Read-AwxAutoconsumeRequest([string] root, [string] readyPath) -> PSCustomObject`
- Produces: `Get-AwxAutoconsumeResult([string] root, [string] requestId) -> PSCustomObject|null`
- Produces: `Write-AwxAutoconsumeResult(...) -> string path`

- [ ] **Step 1: Add failing Inspect tests**

Add literal behavioral cases:

- zero ready files returns exit 0, state `inspected`, reason `queue-empty`;
- two ready files return nonzero, reason `autoconsume-queue-ambiguous`;
- a ready/request hash mismatch returns `autoconsume-request-invalid`;
- an expired request returns `autoconsume-request-expired`;
- a request path containing separators, drive syntax, control characters, or
  reserved Windows names is rejected;
- an existing result with the same request/hash returns `already-processed`
  without file mutation;
- an existing result with the same request ID but a different hash returns
  `autoconsume-request-replay-conflict`.

- [ ] **Step 2: Run RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_autoconsume_contract_tests.ps1 -Suite Inspect
```

Expected: nonzero exit with `consumer-entrypoint-missing`.

- [ ] **Step 3: Implement queue and strict request readers**

Require a production Desktop root equal to
`C:\AbandonWare\demo-1\demo-1\src`. A different root must contain the
literal sentinel `.awx-patchdrop-autoconsume-test-root`. Resolve every path
again and reject reparse traversal. Parse request and ready files as strict
UTF-8 and cap each at 64 KiB.

- [ ] **Step 4: Implement result idempotency**

Store one result at:

```text
data/agent-handoff/patchdrop-autoconsume/<requestId>.json
```

Use atomic UTF-8 publication. The result writer accepts only the five states
and allowlisted reason strings defined in the design. It must not accept an
arbitrary output object.

- [ ] **Step 5: Implement the consumer Inspect mode**

```powershell
[CmdletBinding()]
param(
    [ValidateSet('Inspect','Consume')][string]$Mode = 'Inspect',
    [string]$Root = 'C:\AbandonWare\demo-1\demo-1\src'
)
```

Inspect never calls promotion, lease, apply, Git mutation, Gradle, or file
movement.

- [ ] **Step 6: Run GREEN**

Run the Inspect suite again. Expected: all cases pass and the real
`Y:\__patch_drop__` top-level patch/lease counts remain unchanged.

- [ ] **Step 7: Record checkpoint without committing**

Suggested commit message only:

```text
feat: inspect PatchDrop autoconsume requests safely
```

---

### Task 3: Desktop Promotion, Lease, Apply, Verification, and Rollback

**Files:**
- Modify: `scripts/modules/PatchDropAutoconsume.psm1`
- Modify: `scripts/desktop_patchdrop_autoconsume.ps1`
- Modify: `scripts/desktop_patchdrop_autoconsume_contract_tests.ps1`

**Interfaces:**
- Produces: `Invoke-AwxTrustedVerification([string] root, [string] profile, [string] patch) -> PSCustomObject`
- Produces: `Invoke-AwxVerifiedRollback([string] root, [string] patch) -> PSCustomObject`
- Produces: `Invoke-AwxDesktopAutoconsume([string] root) -> PSCustomObject`

- [ ] **Step 1: Add failing Consume tests**

The tests must use executable fixture helpers that mimic promotion, lease, and
apply only inside a temp root. Assert:

- missing helper exits with a specific `autoconsume-helper-missing` reason;
- an index lock blocks before promotion;
- a top-level patch or active lease blocks before promotion;
- the consumer passes only the validated topic/node to promotion;
- the consumer begins a `desktop-consumer` lease before apply;
- the apply helper receives the exact manifest-pinned top-level patch and owner;
- profile names cannot inject a command;
- successful verification releases the lease and writes `applied`;
- verification failure performs reverse-check and reverse-apply, verifies the
  preimage, releases the lease, and writes `rolled-back`;
- failed reverse-check retains the lease and writes `rollback-required`;
- a second invocation does not apply the same request again.

- [ ] **Step 2: Run RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_autoconsume_contract_tests.ps1 -Suite Consume
```

Expected: nonzero exit because `Invoke-AwxDesktopAutoconsume` is absent.

- [ ] **Step 3: Implement helper invocation with exact arguments**

Resolve these helpers under the chosen root:

```text
__patch_drop__/janitor_promote_producer_pending.ps1
__patch_drop__/source_edit_session.ps1
__patch_drop__/janitor_apply_one.ps1
```

Invoke them through a bounded process runner that captures exit code, elapsed
time, line count, and output SHA-256. Do not store raw output in the result.

- [ ] **Step 4: Implement trusted verification profiles**

Commands are selected only by a closed `switch` in the module. Use
host-local Gradle cache variables for Java profiles. Every profile ends with:

```powershell
git apply --reverse --check $PatchPath
```

The `powershell-tooling` profile runs:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_autoconsume_contract_tests.ps1 -Suite All
```

No request field may become executable text.

- [ ] **Step 5: Implement verified rollback**

When verification fails:

1. run reverse-check;
2. run reverse-apply only if reverse-check succeeds;
3. run forward `git apply --check` against the original patch to prove the
   preimage was restored;
4. end the lease only after that proof;
5. otherwise keep the lease and return `rollback-required`.

- [ ] **Step 6: Implement successful bundle disposition**

Only after verification succeeds, move the five top-level bundle files into
`__patch_drop__/applied/auto-<requestId>/`. Move the request and ready marker
into the same directory after writing the result. If disposition fails, return
`bundle-disposition-failed` without claiming Desktop PASS.

- [ ] **Step 7: Run GREEN and the full suite**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_autoconsume_contract_tests.ps1 -Suite Consume
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_autoconsume_contract_tests.ps1 -Suite All
```

Expected: all cases pass, no secret hits, no changes under the real PatchDrop
queue.

- [ ] **Step 8: Record checkpoint without committing**

Suggested commit message only:

```text
feat: consume PatchDrop requests with Desktop rollback
```

---

### Task 4: Stable Desktop Task Preflight Entrypoint

**Files:**
- Create: `scripts/desktop_task_preflight.ps1`
- Modify: `scripts/desktop_patchdrop_autoconsume_contract_tests.ps1`

**Interfaces:**
- Consumes: `desktop_patchdrop_autoconsume.ps1 -Mode Consume`
- Produces: one compact startup result and the consumer exit code

- [ ] **Step 1: Add failing preflight tests**

Assert:

- an empty queue exits 0 with `queue-empty`;
- an applied request exits 0 with `verified`;
- HOLD and rollback-required results exit nonzero;
- the preflight invokes the consumer exactly once;
- the preflight accepts no topic, patch name, command, or verification override.

- [ ] **Step 2: Run RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_autoconsume_contract_tests.ps1 -Suite Preflight
```

Expected: nonzero exit with `desktop-preflight-entrypoint-missing`.

- [ ] **Step 3: Implement the thin preflight**

```powershell
[CmdletBinding()]
param(
    [string]$Root = 'C:\AbandonWare\demo-1\demo-1\src'
)
```

Resolve the consumer beside the script, invoke `-Mode Consume -Root $Root`
once, relay its compact JSON, and return its exit code.

- [ ] **Step 4: Run GREEN**

Run the Preflight and All suites. Expected: all pass.

- [ ] **Step 5: Record checkpoint without committing**

Suggested commit message only:

```text
feat: add Desktop task-start autoconsume preflight
```

---

### Task 5: Install the Desktop Codex Startup Instruction

**Files:**
- Modify only after ownership proof: `AGENTS.md`

**Precondition:** Current `AGENTS.md` dirty overlap is resolved by its Desktop
owner, and an immediate preimage hash is recorded. Until then this entire task
is HOLD and no substitute global prompt may be modified.

- [ ] **Step 1: Re-run ownership and overlap checks**

Run from the Desktop canonical root:

```powershell
git status --short -- AGENTS.md
Get-FileHash .\AGENTS.md -Algorithm SHA256
Test-Path .\.git\index.lock
```

Expected: no unowned dirty overlap and no index lock.

- [ ] **Step 2: Add the smallest durable instruction**

Add one rule under the Desktop/PatchDrop section:

```text
At the start of each Desktop Codex repository task, run
scripts\desktop_task_preflight.ps1 from the Desktop canonical root before any
source mutation. Treat queue-empty as a successful no-op. Stop the task's source
mutation on any other nonzero result; never bypass, retry, or run a producer
command from a request.
```

- [ ] **Step 3: Verify the instruction does not weaken existing rules**

Confirm it preserves Desktop final ownership, manual empty-queue behavior, one
active v3 bundle, source lease, secret scan, rollback, and no arbitrary producer
commands.

- [ ] **Step 4: Record checkpoint without committing**

Suggested commit message only:

```text
docs: run PatchDrop autoconsume at Desktop task start
```

---

### Task 6: Final Temp-Root Proof and Desktop Canary Handoff

**Files:**
- Modify only if evidence wording needs correction: `docs/superpowers/specs/2026-07-31-desktop-patchdrop-autoconsume-design.md`

- [ ] **Step 1: Run the complete contract suite**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_autoconsume_contract_tests.ps1 -Suite All
```

Expected: exit 0, zero failed assertions, `realPatchDropUntouched=true`.

- [ ] **Step 2: Run existing PatchDrop core guards**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1 -Suite CoreGuards
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
python .\scripts\awx_mcp_completion_audit.py --root .
```

Expected: no regression in janitor/lease contracts. Existing unrelated dirty
state must be reported separately rather than attributed to this feature.

- [ ] **Step 3: Run count-only secret scan**

Scan the module, entrypoints, tests, design, and plan. Expected:
`secretPatternHits=0`.

- [ ] **Step 4: Produce Desktop canary directive**

The canary uses one `documentation-only` Notebook local-worktree bundle.
Desktop runs the task preflight once and records the result. Do not use a Java,
model, environment, DB, or credential change as the first canary.

- [ ] **Step 5: Preserve final status**

Before Desktop canary evidence:

```text
runtimeLineageVerdict=HOLD
desktopFinalProof=evidence_needed
```

After Desktop applies and verifies the canary:

```text
runtimeLineageVerdict=PASS
desktopFinalProof=PASS
```

No Notebook-only evidence may change those fields to PASS.
