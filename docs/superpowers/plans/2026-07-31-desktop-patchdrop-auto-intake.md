# Desktop PatchDrop Automatic Intake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build disabled-by-default Desktop-owned tooling that automatically inventories PatchDrop and can invoke the existing guarded consumer only when an explicit Desktop-local APPLY policy and every fail-closed gate pass.

**Architecture:** A bounded PowerShell consumer parses one strict policy, inventories only top-level cumulative v3 bundles, derives a redacted decision, and delegates application to the existing janitor script. A separate task manager renders, installs, audits, or removes one exact Scheduled Task; Notebook work exercises render and temp-fixture modes only.

**Tech Stack:** Windows PowerShell 5.1, JSON policy and ledger rows, Windows Scheduled Tasks, existing PatchDrop v3/janitor/lease scripts, temp-only PowerShell contract tests.

## Global Constraints

- Work from canonical `Y:\`; never print or persist a raw mapped remote path or UNC Git root.
- Request class is `prompt_skill_tooling_only`; do not modify `main/**`, `app/**`, Gradle, DB, providers, credentials, environment variables, or application source.
- Repository tooling may be created and tested; real Desktop task registration, source apply, and activation remain `desktopFinalProof=evidence_needed`.
- The sample policy is `enabled=false` and `mode=OBSERVE` with empty allowlists.
- `APPLY` requires a Desktop-local policy outside the repository plus identity, ACL, allowlist, queue, lease, preimage, budget, janitor, verification, and rollback proof.
- Never create a key, certificate, credential, task on a remote host, WMI subscription, service, startup registry value, or global Git trust entry.
- Never select a patch by timestamp. Accept exactly one manifest-pinned top-level cumulative v3 bundle.
- Never reimplement patch application. Invoke `__patch_drop__\janitor_apply_one.ps1` by bare patch name after all pre-gates pass.
- Ledger output contains allowlisted hashes, counts, booleans, timing, and failure classes only.
- Commits, staging, pushes, and deployment are forbidden unless separately authorized.
- Time budget is a 180-minute hard cap; stop when focused artifact proof is complete.

---

## File Structure

### Create

- `scripts/desktop_patchdrop_auto_intake.ps1` — strict policy parser, scanner, decision engine, idempotency lock, janitor adapter, and redacted ledger writer.
- `scripts/desktop_patchdrop_auto_intake_task.ps1` — render/install/status/uninstall manager for one exact Scheduled Task.
- `scripts/desktop_patchdrop_auto_intake.policy.sample.json` — disabled OBSERVE sample without live hashes or trust values.
- `scripts/desktop_patchdrop_auto_intake_contract_tests.ps1` — temp-only RED/GREEN tests using a fake janitor adapter.
- `data/agent-handoff/desktop-patchdrop-auto-intake/README.md` — redacted runtime-state schema and Desktop activation handoff; no live state file.

### Reuse without modification

- `__patch_drop__/janitor_inventory.ps1`
- `__patch_drop__/janitor_apply_one.ps1`
- `__patch_drop__/source_edit_lease_contract.ps1`
- `__patch_drop__/producer_bundle.ps1`
- `__patch_drop__/producer_bundle.py`
- `.agents/skills/demo1-patchdrop-manual-default/**`
- `.agents/skills/patchdrop-safe-patch-orchestrator/**`

---

### Task 1: Strict policy and top-level queue scanner

**Files:**
- Create: `scripts/desktop_patchdrop_auto_intake.ps1`
- Create: `scripts/desktop_patchdrop_auto_intake.policy.sample.json`
- Create: `scripts/desktop_patchdrop_auto_intake_contract_tests.ps1`

**Interfaces:**
- `Read-AwxAutoIntakePolicy([string]$Path) -> PSCustomObject`
- `Get-AwxTopLevelBundleSnapshot([string]$Root) -> PSCustomObject`
- `Get-AwxAutoIntakeDecision([object]$Policy,[object]$Snapshot,[object]$Evidence) -> PSCustomObject`
- Decision fields: `decision`, `failureClass`, `mode`, `patchName`, `bundleIdHash`, `queueCount`, `validatorVersion`.

- [ ] **Step 1: Write failing policy tests**

Add tests that create temp policies and assert:

```powershell
$sample = Get-Content -Raw -Encoding UTF8 $samplePolicy | ConvertFrom-Json
Test-Contract 'sample disabled' (-not [bool]$sample.enabled)
Test-Contract 'sample observe only' ([string]$sample.mode -ceq 'OBSERVE')
Test-Contract 'sample allowlists empty' (@($sample.allowedTopics).Count -eq 0)
Test-Contract 'sample contains no live identity' ([string]::IsNullOrEmpty([string]$sample.expectedBackingShareIdentitySha256))
```

Malformed JSON, missing/extra fields, wrong schema, non-boolean `enabled`, invalid mode, invalid SHA format, empty APPLY allowlists, negative budgets, and intervals outside `1..60` must return `HOLD/policy-invalid` without throwing raw content.

- [ ] **Step 2: Run tests to verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_contract_tests.ps1
```

Expected: exit `1`; missing scripts/functions and sample-policy assertions fail.

- [ ] **Step 3: Create the disabled sample policy**

Use exactly:

```json
{
  "schemaVersion": "awx.desktop-patchdrop-auto-intake.policy.v1",
  "enabled": false,
  "mode": "OBSERVE",
  "expectedBackingShareIdentitySha256": "",
  "expectedShareAclSha256": "",
  "allowedNodes": [],
  "allowedTopics": [],
  "allowedPathPrefixes": [],
  "maxPatchBytes": 1048576,
  "maxChangedFiles": 20,
  "maxHunks": 100,
  "pollIntervalMinutes": 5,
  "verificationProfile": "focused"
}
```

- [ ] **Step 4: Implement strict policy parsing**

`Read-AwxAutoIntakePolicy` must:

```powershell
$required = @(
  'schemaVersion','enabled','mode','expectedBackingShareIdentitySha256',
  'expectedShareAclSha256','allowedNodes','allowedTopics','allowedPathPrefixes',
  'maxPatchBytes','maxChangedFiles','maxHunks','pollIntervalMinutes',
  'verificationProfile'
)
```

Reject missing or extra fields, wrong types, non-ASCII enum values, duplicate allowlist entries, path traversal, absolute/UNC allowlist paths, live secrets, and unbounded arrays. Return a redacted exception reason code; never include JSON content.

- [ ] **Step 5: Add failing scanner tests**

Temp fixtures must cover:

- zero top-level patches → `NOOP/queue-empty`;
- nested producer patch only → `NOOP/queue-empty`;
- two top-level patches → `HOLD/patch-drop-pending`;
- one patch missing any sidecar → `HOLD/missing-bundle-meta`;
- non-v3 name or manifest activePatch mismatch → `HOLD/manifest-invalid`;
- one complete manifest-pinned v3 → one candidate with bare patch name.

- [ ] **Step 6: Implement the top-level scanner and decision baseline**

The scanner uses `Get-ChildItem -LiteralPath $PatchDrop -File -Filter '*-v3.patch'` and checks exact sibling names. It reads manifest JSON with strict UTF-8 and bounded bytes. It returns filenames and hashes only; no patch body or raw paths.

The baseline decision order is:

```text
policy invalid -> HOLD
policy disabled -> NOOP
queue empty -> NOOP
queue ambiguous/incomplete -> HOLD
mode OBSERVE + valid candidate -> OBSERVE
mode APPLY -> HOLD/apply-preconditions-unproven (until Task 2)
```

- [ ] **Step 7: Run Task 1 GREEN**

Run the focused test and require exit `0`, `failed=0`, plus a count-only secret scan over the three new files.

- [ ] **Step 8: Prepare but do not execute a commit boundary**

Allowed paths would be the three Task 1 files. Record `commit=not_authorized`.

---

### Task 2: APPLY gates, idempotency, adapter, and ledger

**Files:**
- Modify: `scripts/desktop_patchdrop_auto_intake.ps1`
- Modify: `scripts/desktop_patchdrop_auto_intake_contract_tests.ps1`

**Interfaces:**
- `Test-AwxAutoIntakeApplyEvidence([object]$Policy,[object]$Snapshot,[object]$Evidence) -> PSCustomObject`
- `Enter-AwxAutoIntakeLock([string]$StateRoot) -> IDisposable-like lock object`
- `Invoke-AwxJanitorAdapter([string]$Root,[string]$PatchName,[string]$AdapterPath,[switch]$TestMode) -> PSCustomObject`
- `Write-AwxAutoIntakeLedger([string]$StateRoot,[object]$Decision) -> string hash`

- [ ] **Step 1: Add failing APPLY gate tests**

Create redacted evidence objects with exact fields:

```text
canonicalRootProven
backingShareIdentityVerified
shareAclHashMatch
indexLock
activeLeaseCount
dirtyOverlapCount
preimageMatch
secretPatternHitCount
sourceIsolationPass
patchBytes
changedFileCount
hunkCount
rollbackReady
```

Each false/mismatched gate must produce its specific HOLD/REJECT failure and must leave the fake-adapter call count at zero.

- [ ] **Step 2: Run RED and record the failure matrix**

Expected failures include identity, ACL, index lock, lease, dirty overlap, preimage, secret, source isolation, patch/file/hunk budgets, and rollback readiness.

- [ ] **Step 3: Implement APPLY evidence validation**

Apply decisions require all booleans and counts to pass, manifest node/topic/path allowlists to match case-sensitively after slash normalization, and no undeclared fields. `secretPatternHitCount>0` is `REJECT`; other missing proof is `HOLD`.

- [ ] **Step 4: Add failing lock and replay tests**

Test atomic single-instance locking, a corrupt lock, stale lock metadata without process ownership proof, duplicate bundle hash, state corruption, and two simultaneous temp processes. No test may delete outside its GUID-owned temp root.

- [ ] **Step 5: Implement lock and processed-hash state**

Use `FileMode.CreateNew`, `FileShare.None`, bounded JSON, and same-directory temp-write/flush/hash/rename. A duplicate manifest+patch hash returns `NOOP/already-processed`. Never steal a live lock.

- [ ] **Step 6: Add failing adapter tests**

The fake adapter records its bare patch-name argument. Assert:

- OBSERVE never calls it;
- valid APPLY calls it exactly once;
- path-bearing patch names are rejected;
- nonzero adapter exit produces `HOLD/janitor-failed`;
- apply success without focused verification or rollback evidence is not recorded as processed.

- [ ] **Step 7: Implement the janitor adapter**

Production adapter path must resolve exactly beneath the proven root and end in `__patch_drop__\janitor_apply_one.ps1`. Invoke:

```powershell
& powershell -NoProfile -ExecutionPolicy Bypass -File $AdapterPath -PatchName $PatchName
```

Capture bounded stdout/stderr hashes and exit code, not raw output. Arbitrary adapter paths are allowed only with `-TestMode` and a temp-root containment check.

- [ ] **Step 8: Implement the redacted ledger**

Allow only the decision schema from the design. Append one compact JSON row after serializing to a temp sibling, flushing, hashing, and atomically renaming. Enforce a 4 KiB row limit and rotate by count, never by unbounded log size.

- [ ] **Step 9: Run Task 2 GREEN**

Require full focused tests green, fake adapter exact-call assertions, count-only secret hits `0`, and no writes outside temp fixtures.

---

### Task 3: Scheduled Task render/install/uninstall manager

**Files:**
- Create: `scripts/desktop_patchdrop_auto_intake_task.ps1`
- Modify: `scripts/desktop_patchdrop_auto_intake_contract_tests.ps1`

**Interfaces:**
- Parameters: `-Action Render|Install|Status|Uninstall`, `-PolicyPath`, `-OutputPath`, `-ConfirmActionSha256`, `-TestMode`.
- `New-AwxAutoIntakeTaskDefinition(...) -> PSCustomObject`
- `Get-AwxAutoIntakeTaskActionSha256(...) -> string`

- [ ] **Step 1: Write failing render tests**

The rendered definition must use one fixed task name, `powershell.exe`, `-NoProfile -NonInteractive -ExecutionPolicy Bypass`, the consumer path, Desktop-local policy path, interval `1..60`, single-instance ignore-new behavior, bounded execution time, and no credential/password field.

- [ ] **Step 2: Run RED**

Expected: missing manager and render functions fail.

- [ ] **Step 3: Implement deterministic Render**

Render emits bounded JSON and optional XML to an explicit path. It must not call `Register-ScheduledTask`. Two identical inputs produce byte-identical output and action hash.

- [ ] **Step 4: Add failing Install guards**

Install must HOLD unless all are true:

- running locally on the proven Desktop node/root;
- policy path is Desktop-local and outside the repository;
- consumer and policy hashes match `-ConfirmActionSha256`/policy evidence;
- existing task is absent or has the exact same action hash;
- current policy is `enabled=true`;
- `ShouldProcess` confirms the exact task name.

Notebook and `-TestMode` may render only; they must never reach task registration.

- [ ] **Step 5: Implement Install and Status**

Use `Register-ScheduledTask` only inside the guarded Install branch. Status returns task presence, state, and action hash only; it must not emit raw arguments or paths.

- [ ] **Step 6: Add and implement Uninstall guards**

Uninstall requires `-ConfirmActionSha256` equal to the installed task action hash and removes only the exact task name. Mismatch returns `HOLD/task-action-changed`.

- [ ] **Step 7: Run Task 3 GREEN**

Use temp/render fixtures only. Confirm no matching real Scheduled Task count or state changed before/after the test.

---

### Task 4: Handoff documentation and focused integration verification

**Files:**
- Create: `data/agent-handoff/desktop-patchdrop-auto-intake/README.md`
- Verify: all Task 1-3 files

**Interfaces:**
- Produces a Desktop SourceDirective and exact render/install/verify/uninstall evidence contract.

- [ ] **Step 1: Write the handoff contract**

Document:

```text
toolingArtifactVerdict
activationVerdict
policySha256
consumerSha256
taskDefinitionSha256
taskActionSha256
observeProof
invalidFailClosedProof
applyFixtureProof
rollbackProof
desktopFinalProof
```

Do not include live paths, task arguments, ACL rows, mappings, secrets, or source snippets.

- [ ] **Step 2: Run focused tests**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_contract_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1 -Suite CoreGuards
```

Expected: exit `0`, all focused tests pass.

- [ ] **Step 3: Run count-only secret and mutation scans**

Scan only declared files. Expected `secretPatternHitCount=0` and application-source changed-by-this-task count `0`.

- [ ] **Step 4: Render the Desktop task without installing**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_task.ps1 -Action Render -PolicyPath .\scripts\desktop_patchdrop_auto_intake.policy.sample.json -OutputPath $env:TEMP\awx-auto-intake-task.json -TestMode
```

Expected: deterministic redacted definition, sample policy disabled, `activationVerdict=HOLD`.

- [ ] **Step 5: Record final Notebook verdict**

```text
toolingArtifactVerdict=APPLY
activationVerdict=HOLD
activationReason=desktop-install-and-live-policy-proof-missing
applicationSourceMutation=false
secretPatternHitCount=0
desktopFinalProof=evidence_needed
commit=not_authorized
```

- [ ] **Step 6: Prepare but do not execute the Desktop SourceDirective**

The directive must first copy the sample to a Desktop-local policy, fill only
hash/allowlist values proven by Desktop, run OBSERVE and invalid-fixture proof,
then separately choose APPLY. Notebook must not run the Install command.

---

## Rollback

Repository rollback removes only the five created files. Desktop activation
rollback is separate: verify the installed action hash, uninstall the exact task,
preserve the redacted ledger, and use existing janitor rollback evidence for any
applied bundle. Never recursively delete a state root without validating its
resolved Desktop-local path.

## Completion Boundary

Notebook completion proves disabled-by-default tooling, deterministic task
rendering, fail-closed decisions, fake-adapter behavior, and compatibility with
existing janitor guards. It does not prove the real task is installed or that a
canonical patch was applied. Real activation remains `HOLD` until Desktop-owned
evidence satisfies the design's activation criteria.

