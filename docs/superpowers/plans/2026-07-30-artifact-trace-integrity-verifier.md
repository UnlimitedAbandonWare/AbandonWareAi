# Artifact Trace Integrity Verifier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded read-only verifier and Korean Markdown harness that detect post-publication artifact-trace changes without upgrading files into patch, approval, Desktop, deployment, or runtime proof.

**Architecture:** Keep the existing capture generator behavior-compatible and add a separate PowerShell verifier beside it. The verifier requires an out-of-band expected manifest SHA, authenticates one trace envelope, compares its declared recorded row set with two equal full-content current inventories, and atomically publishes a separately checksummed `VERIFICATION_ONLY` packet; a repo-local Markdown harness routes capture versus recheck through the existing skill.

**Tech Stack:** Windows PowerShell 5.1-compatible scripts, JSON, SHA-256, repo-local Codex skills, Markdown/YAML prompt harnesses, Python skill validators.

## Global Constraints

- Request class is `prompt_skill_tooling_only`; do not change `main/**`, `app/**`, `project/**`, Gradle, public APIs, databases, credentials, environment variables, providers, or application runtime behavior.
- Read only one explicit `data/agent-handoff/artifact-trace/<traceId>` source trace and its manifest-declared allowlisted input root.
- Write only one new
  `data/agent-handoff/artifact-trace-verification/<verificationId>/` directory.
- Never delete, move, quarantine, apply, unlock, repair, supersede, or rewrite an input artifact or original trace.
- Never choose a source trace, input, bundle, or verification by newest timestamp.
- Keep `overallVerdict=VERIFICATION_ONLY`, `mutationAllowed=false`,
  `deleteAuthorized=false`, `runtimeLineageVerdict=HOLD`, and
  `desktopFinalProof=evidence_needed`.
- Integrity verdict precedence is `INVALID`, `INDETERMINATE`, `CHANGED`,
  `UNCHANGED`.
- Capture v1 is a non-atomic recorded row set; expose
  `captureSnapshotAtomicity=unproven`.
- `UNCHANGED` proves only equality with that recorded row set during two equal
  bounded full-content scans.
- Default limits remain 512 files, 64 MiB total input, 2 MiB JSON, 120 seconds, and 1 MiB output.
- Do not change global Git trust. `//DESKTOP-M5NOV6K/MacSrc` currently reports dubious ownership; use exact file SHA-256 checkpoints.
- Do not commit, push, merge, deploy, or send external messages without a separate explicit user instruction. Replace plan commit steps with checkpoint hash records.
- Superpowers is `supporting_process`; repository evidence is `authoritative`.

---

## File Map

| File | Responsibility |
| --- | --- |
| `.agents/skills/demo1-artifact-trace-curator/scripts/test_artifact_trace_manifest.ps1` | Authenticate a captured trace, compare a stable current snapshot, and publish a verification packet |
| `scripts/demo1_artifact_trace_integrity_verifier_contract_tests.ps1` | Temp-only RED/GREEN fixtures for envelope, comparison, publication, and harness behavior |
| `agent-prompts/agents/demo1_artifact_trace_integrity_harness/system_ko.md` | Korean operator contract for selecting capture or recheck without authority expansion |
| `agent-prompts/agents/demo1_artifact_trace_integrity_harness/meta.yaml` | Prompt discovery metadata |
| `agent-prompts/prompts.manifest.yaml` | Global prompt-pack registration and UTF-8 output path |
| `agent-prompts/out/demo1_artifact_trace_integrity_harness.prompt` | Deterministically built prompt output |
| `docs/superpowers/evidence/2026-07-30-artifact-trace-integrity-harness-pressure-tests.md` | Compact transcripts from three fresh read-only behavior probes |
| `.agents/skills/demo1-artifact-trace-curator/SKILL.md` | Trigger, routing, exact capture/recheck commands, and proof limits |
| `.agents/skills/demo1-artifact-trace-curator/references/artifact-trace-contract.md` | Verification schema, verdict, failure, and publication reference |
| `.agents/skills/demo1-artifact-trace-curator/agents/openai.yaml` | UI wording updated from capture-only to capture-or-recheck |

Do not split shared hashing functions out of the existing 645-line capture generator in this pass. A helper extraction would expand regression scope without being required for the new verifier.

### Task 1: Establish the Verifier RED Contract

**Files:**

- Create: `scripts/demo1_artifact_trace_integrity_verifier_contract_tests.ps1`
- Read only: `.agents/skills/demo1-artifact-trace-curator/scripts/new_artifact_trace_manifest.ps1`
- Expected missing implementation:
  `.agents/skills/demo1-artifact-trace-curator/scripts/test_artifact_trace_manifest.ps1`

**Interfaces:**

- Consumes: existing capture generator parameters `Root`, `TraceId`, `InputRoot`.
- Produces: a test entry point with
  `param([ValidateSet('Envelope','Compare','Publication','Harness','All')] [string]$Group='All')`.
- Produces fixture helpers `New-CapturedTrace`, `Invoke-Verifier`,
  `Read-VerificationPacket`, `Assert-ThrowsReason`, and `Write-TestSummary`.

- [ ] **Step 1: Write the group-aware failing test harness**

Start the file with:

```powershell
[CmdletBinding()]
param(
    [ValidateSet('Envelope', 'Compare', 'Publication', 'Harness', 'All')]
    [string]$Group = 'All'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$captureScript = Join-Path $repoRoot '.agents\skills\demo1-artifact-trace-curator\scripts\new_artifact_trace_manifest.ps1'
$verifyScript = Join-Path $repoRoot '.agents\skills\demo1-artifact-trace-curator\scripts\test_artifact_trace_manifest.ps1'
$harnessPath = Join-Path $repoRoot 'agent-prompts\agents\demo1_artifact_trace_integrity_harness\system_ko.md'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'awx-artifact-trace-verify-' + [guid]::NewGuid().ToString('N')
)
```

Implement `New-CapturedTrace` so every case creates an isolated temporary root,
writes one or more files under `data/agent-handoff/fixture-<case>`, invokes the
existing capture script, and returns:

```powershell
[pscustomobject]@{
    root = $CaseRoot
    inputRelative = 'data/agent-handoff/fixture-' + $Case
    inputFull = $inputFull
    traceId = $traceId
    traceRelative = 'data/agent-handoff/artifact-trace/' + $traceId
    traceFull = $traceFull
    expectedManifestSha256 = $captureResult.manifestSha256
}
```

Before running any group, require the verifier:

```powershell
if (-not (Test-Path -LiteralPath $verifyScript -PathType Leaf)) {
    throw 'verifier-missing'
}
```

Always remove only `$testRoot` in `finally` and assert it no longer exists.
Print only:

```text
[artifact-trace-verify-test][PASS|FAIL|SKIP] <case> reason=<reason-code>
[artifact-trace-verify-test][SUMMARY] group=<group> passed=<n> failed=<n> skipped=<n>
```

- [ ] **Step 2: Add the first Envelope fixture**

Capture one file, invoke the absent verifier with:

```powershell
& $verifyScript `
  -Root $fixture.root `
  -TraceDirectory $fixture.traceRelative `
  -ExpectedManifestSha256 $fixture.expectedManifestSha256 `
  -VerificationId 'envelope-valid'
```

The future packet path is:

```powershell
Join-Path $fixture.root 'data\agent-handoff\artifact-trace-verification\envelope-valid\verification.json'
```

Assert that an authenticated trace can eventually produce schema
`awx.artifact_trace_verification.v1` and `overallVerdict=VERIFICATION_ONLY`.

- [ ] **Step 3: Run the test to prove RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\demo1_artifact_trace_integrity_verifier_contract_tests.ps1 `
  -Group Envelope
```

Expected: exit 1 with exactly `verifier-missing` plus successful temporary
fixture cleanup. A syntax error or missing capture generator is not acceptable
RED evidence.

- [ ] **Step 4: Record the RED checkpoint**

Run:

```powershell
Get-FileHash -Algorithm SHA256 `
  .\scripts\demo1_artifact_trace_integrity_verifier_contract_tests.ps1
```

Record the lowercase hash and the concise RED summary in the session report. Do
not commit.

### Task 2: Authenticate the Source Trace Envelope

**Files:**

- Create:
  `.agents/skills/demo1-artifact-trace-curator/scripts/test_artifact_trace_manifest.ps1`
- Modify: `scripts/demo1_artifact_trace_integrity_verifier_contract_tests.ps1`

**Interfaces:**

- Consumes:
  `-Root <string> -TraceDirectory <string> -ExpectedManifestSha256 <sha256>
  -VerificationId <slug>` and the five
  bounded integer parameters.
- Produces internal `Get-AuthenticatedEnvelope` with fields `valid`,
  `integrityVerdict`, `traceId`, `manifest`, `expectedManifestSha256`,
  `manifestSha256`, `sidecarSha256`, `readySha256`, exact membership/types,
  `inputRoot`, and `failureClassifications`.
- Produces a minimal `Write-VerificationPacket` used by every later verdict.

- [ ] **Step 1: Add failing Envelope cases**

Add fixtures and exact expectations:

| Case | Mutation | Expected |
| --- | --- | --- |
| valid envelope | none | not `INVALID` |
| stale sidecar | append one byte to manifest | `INVALID` + `trace-verify-manifest-sha-mismatch` |
| fully rebound envelope | rewrite manifest and recompute sidecar and ready | `INVALID` + `trace-verify-expected-manifest-sha-mismatch` |
| altered sidecar | replace hash with 64 zeroes | `INVALID` + `trace-verify-manifest-sha-mismatch` |
| missing ready | remove temp fixture ready marker | `INVALID` + `trace-verify-ready-binding-invalid` |
| altered ready | replace `manifestSha256` | `INVALID` + `trace-verify-ready-binding-invalid` |
| extra envelope file | add `note.txt` beside manifest | `INVALID` + `trace-verify-envelope-invalid` |
| wrong invariant | recalculate sidecar after changing `mutationAllowed` to true | `INVALID` + `trace-verify-manifest-contract-invalid` |
| traversal path | recalculate sidecar after inserting `../outside.txt` artifact path | `INVALID` + `trace-verify-manifest-contract-invalid` |

Each mutation is confined to its temp fixture. For manifest mutations, rewrite
UTF-8 without BOM and recompute the sidecar only in the cases intended to test
manifest contract rather than sidecar mismatch.

- [ ] **Step 2: Run Envelope and confirm the new assertions fail**

Run the Envelope command from Task 1.

Expected: tests now reach the verifier but fail because envelope authentication
functions and `INVALID` packet publication are absent.

- [ ] **Step 3: Implement parameters and safe path helpers**

Use:

```powershell
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string]$TraceDirectory,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-f0-9]{64}$')]
    [string]$ExpectedManifestSha256,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,63}$')]
    [string]$VerificationId,
    [ValidateRange(0, 4096)][int]$MaxFiles = 512,
    [ValidateRange(0, 1024)][int]$MaxTotalMiB = 64,
    [ValidateRange(0, 16)][int]$MaxJsonMiB = 2,
    [ValidateRange(0, 600)][int]$TimeoutSeconds = 120,
    [ValidateRange(0, 16)][int]$MaxOutputMiB = 1
)
```

Copy the proven semantics, not the prose, of these capture helpers into the new
script: `Get-FullNormalizedPath`, `Test-PathEqual`,
`Test-StrictDescendant`, `Get-RelativePathSafe`, `Assert-NoReparseChain`,
`Get-Sha256Bytes`, `Get-Sha256Text`, `Get-LockedSnapshot`,
`Get-PropertyValue`, `Test-HasProperty`, and `Test-SecretLikeText`.

Keep them verifier-local. Name verifier reasons with `trace-verify-`.

- [ ] **Step 4: Implement envelope authentication**

Require the source directory to contain exactly:

```powershell
$expectedEnvelopeNames = @('.ready', 'manifest.json', 'manifest.sha256')
$actualEnvelopeNames = @(
    Get-ChildItem -LiteralPath $traceFull -Force |
    Sort-Object Name |
    Select-Object -ExpandProperty Name
)
```

Parse `manifest.sha256` with:

```powershell
if ($sidecarText -cne ($manifestSha + '  manifest.json' + [Environment]::NewLine)) {
    return New-InvalidEnvelope 'trace-verify-manifest-sha-mismatch'
}
```

Parse the ready marker as key/value lines and require:

```text
schemaVersion=awx.artifact_trace_ready.v1
manifestSha256=<actual manifest hash>
```

Before parsing, require the actual hash to equal
`ExpectedManifestSha256`; otherwise classify
`trace-verify-expected-manifest-sha-mismatch`. Read the manifest only after the
actual hash matches both trust anchor and sidecar. Require strict UTF-8/no-BOM,
no NUL/replacement character, bounded structure, no duplicate/case-varied
keys, exact JSON types, and:

```powershell
$manifest.schemaVersion -ceq 'awx.artifact_trace_manifest.v1'
$manifest.overallVerdict -ceq 'INVENTORY_ONLY'
$manifest.mutationAllowed -is [bool] -and $manifest.mutationAllowed -eq $false
$manifest.deleteAuthorized -is [bool] -and $manifest.deleteAuthorized -eq $false
$manifest.runtimeLineageVerdict -ceq 'HOLD'
$manifest.desktopFinalProof -ceq 'evidence_needed'
```

Reject duplicate paths, non-normalized separators, `.`/`..` segments, hashes
outside `^[a-f0-9]{64}$`, negative sizes, unparseable UTC timestamps, and paths
outside normalized `inputRoot`.

- [ ] **Step 5: Implement the final packet shape and ready-last publication**

Use:

```powershell
$packet = [ordered]@{
    schemaVersion = 'awx.artifact_trace_verification.v1'
    verificationId = $VerificationId
    sourceTraceId = $sourceTraceId
    sourceTraceDirectory = $traceRelative
    expectedManifestSha256 = $ExpectedManifestSha256
    sourceManifestSha256 = $manifestSha
    sourceManifestSidecarSha256 = $sidecarSha
    sourceReadySha256 = $readySha
    captureSnapshotAtomicity = 'unproven'
    verifiedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    durationMs = [long]$started.ElapsedMilliseconds
    overallVerdict = 'VERIFICATION_ONLY'
    integrityVerdict = $integrityVerdict
    mutationAllowed = $false
    deleteAuthorized = $false
    runtimeLineageVerdict = 'HOLD'
    desktopFinalProof = 'evidence_needed'
    expectedFileCount = 0
    currentFileCount = 0
    unchangedCount = 0
    changedCount = 0
    missingCount = 0
    addedCount = 0
    metadataOnlyChangedCount = 0
    currentInventorySha256 = $null
    changesTruncated = $false
    omittedChangeCount = 0
    changes = @()
    failureClassifications = @($failureClassifications)
    allowedClaims = @('integrity_verification_packet_present')
    notProofOf = @(
        'freshness', 'source_patch', 'patch_handoff', 'desktop_apply',
        'runtime', 'deployment', 'approval', 'current_state',
        'capture_atomicity'
    )
}
```

Even in Task 2, publish only the final schema under the fixed verification
output root; do not introduce a temporary public schema.

- [ ] **Step 6: Run Envelope to GREEN**

Run the Envelope command.

Expected: all Envelope cases pass, no required case skips, temp roots are
removed, and every `INVALID` packet keeps `VERIFICATION_ONLY`, `HOLD`, and
`evidence_needed`.

- [ ] **Step 7: Record the envelope checkpoint**

Hash the verifier and test file. Record hashes and the Envelope summary. Do not
commit.

### Task 3: Compare Two Stable Full-Content Inventories

**Files:**

- Modify:
  `.agents/skills/demo1-artifact-trace-curator/scripts/test_artifact_trace_manifest.ps1`
- Modify: `scripts/demo1_artifact_trace_integrity_verifier_contract_tests.ps1`

**Interfaces:**

- Consumes authenticated `manifest.inputRoot` and `manifest.artifacts`.
- Produces `Get-CurrentInventory` returning `rows`, `fileCount`, `totalBytes`,
  and `inventorySha256`; two complete invocations must match before a terminal
  changed/unchanged verdict.
- Produces `Compare-Inventory` returning exact counts, bounded changes,
  current inventory hash, and `integrityVerdict`.

- [ ] **Step 1: Add failing Compare cases**

Use one isolated captured trace per case:

| Case | Fixture change | Expected |
| --- | --- | --- |
| unchanged | none | `UNCHANGED`, unchanged 1 |
| content changed | rewrite same path | `CHANGED`, changed 1 |
| metadata only | preserve bytes, set later `LastWriteTimeUtc` | `CHANGED`, metadata-only 1 |
| missing | remove fixture input file | `CHANGED`, missing 1 |
| added | create second input file | `CHANGED`, added 1 |
| mixed | change one, remove one, add one | exact three-type counts |
| unstable | background job repeatedly rewrites one input during scan | never `UNCHANGED`; authenticated case must be `INDETERMINATE` |
| ABA content race | same-size bytes change and timestamp is restored between passes | `INDETERMINATE` |

Assert every `changes` row contains only:

```text
path
changeKind
expectedSha256
currentSha256
expectedSizeBytes
currentSizeBytes
expectedLastWriteTimeUtc
currentLastWriteTimeUtc
```

No row may contain file content.

- [ ] **Step 2: Run Compare and confirm RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\demo1_artifact_trace_integrity_verifier_contract_tests.ps1 `
  -Group Compare
```

Expected: exit 1 because current input enumeration and comparison are absent.

- [ ] **Step 3: Implement stable inventory enumeration**

Use a queue that enumerates one directory at a time and rejects reparse points
before descending. For each file, collect:

```powershell
[ordered]@{
    path = $repoRelativePath.Replace('\', '/')
    sizeBytes = [long]$item.Length
    sha256 = $lockedSnapshot.sha256
    lastWriteTimeUtc = $item.LastWriteTimeUtc.ToString('o')
}
```

Reject ordinal-ignore-case collisions, then sort normalized paths ordinally.
Compute the inventory SHA over
UTF-8 lines:

```text
<path><TAB><size><TAB><sha256><TAB><lastWriteTimeUtc>
```

Run the full content-hash inventory twice. `stableInventory=true` only when the
two canonical inventory byte sequences match and the exact source-envelope
membership, types, manifest SHA, sidecar SHA, and ready SHA still equal the
authenticated values. A size/time-only fingerprint is insufficient.

- [ ] **Step 4: Implement comparison and verdict selection**

Build case-insensitive maps but reject case-colliding duplicate paths. Use:

```powershell
if (-not $stableInventory) {
    $verdict = 'INDETERMINATE'
} elseif ($changedCount + $metadataOnlyChangedCount + $missingCount + $addedCount -gt 0) {
    $verdict = 'CHANGED'
} else {
    $verdict = 'UNCHANGED'
}
```

Use `metadata_only` only when bytes/size match and timestamp differs. Use
`content_changed` when SHA differs, `missing` when expected only, and `added`
when current only. Keep all rows in stable path order.

Set:

```powershell
$allowedClaims = switch ($verdict) {
    'UNCHANGED' { @('bounded_recorded_row_set_match_observed') }
    'CHANGED' { @('bounded_recorded_row_set_difference_observed') }
    'INDETERMINATE' { @('stable_snapshot_not_obtained') }
    default { @('source_trace_invalid') }
}
```

The `notProofOf` list never changes.

- [ ] **Step 5: Bound change rows**

Include change rows in stable order until the next row would exceed
`MaxOutputMiB`, then set:

```powershell
changesTruncated = $true
omittedChangeCount = $totalChangeCount - $changes.Count
```

Aggregate counts remain exact. If the packet without change rows exceeds the
budget, fail `trace-verify-budget-exceeded` and publish no packet.

- [ ] **Step 6: Run Compare to GREEN**

Run the Compare command.

Expected: every case passes; unstable never becomes `UNCHANGED`; no content or
secret-like value appears in packet text.

- [ ] **Step 7: Record the comparison checkpoint**

Hash the verifier and test file and record the Compare summary. Do not commit.

### Task 4: Harden Atomic Publication and Safety Failures

**Files:**

- Modify:
  `.agents/skills/demo1-artifact-trace-curator/scripts/test_artifact_trace_manifest.ps1`
- Modify: `scripts/demo1_artifact_trace_integrity_verifier_contract_tests.ps1`

**Interfaces:**

- Consumes the final packet from Tasks 2–3.
- Produces exactly `verification.json`, `verification.sha256`, and `.ready`
  under a new verification ID.
- Produces compact result JSON on stdout without raw input content.

- [ ] **Step 1: Add failing Publication cases**

Add:

1. `verification.sha256` equals the exact UTF-8 no-BOM JSON hash.
2. `.ready` contains
   `schemaVersion=awx.artifact_trace_verification_ready.v1` and
   `verificationSha256=<actual hash>`.
3. ready last-write time is not earlier than JSON or sidecar.
4. a second run with the same ID fails `trace-verify-output-collision` and
   preserves the first packet hash.
5. source trace and input hashes remain equal before/after an unchanged run.
6. an absolute, root-level, or non-trace source is rejected with
   `trace-verify-source-not-allowlisted` and no packet.
7. reparse source/input is rejected with `trace-verify-reparse-risk` and no
   packet.
8. zero file/byte/JSON/time/output limits fail with the documented bounded
   reason and leave no final directory.
9. a secret-like value in would-be output fails `trace-verify-secret-risk`
   without printing the value.
10. continuous authenticated input mutation produces `INDETERMINATE` with
    `trace-verify-input-unstable`.
11. envelope mutation can never yield `UNCHANGED`; pre-auth change is
    `INVALID`, while post-auth change is `INDETERMINATE` with
    `trace-verify-envelope-changed`.
12. fixture cleanup succeeds with zero skipped required cases.

- [ ] **Step 2: Run Publication and confirm RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\demo1_artifact_trace_integrity_verifier_contract_tests.ps1 `
  -Group Publication
```

Expected: exit 1 until all atomic and fail-closed behavior exists.

- [ ] **Step 3: Implement atomic publication**

Serialize with `ConvertTo-Json -Depth 20` and UTF-8 without BOM. Reject
secret-like output before writing. Use a non-stale process mutex for concurrent
same-ID publishers and an unguessable staging owner token:

```powershell
$temporaryOutput = Join-Path $outputParent (
    '.' + $VerificationId + '.tmp-' + [guid]::NewGuid().ToString('N')
)
[IO.Directory]::CreateDirectory($temporaryOutput) | Out-Null
$temporaryOwnerToken = [guid]::NewGuid().ToString('N')
# Write owner, JSON, sidecar, and ready-last with FileStream.Write + Flush(true).
# Validate owner identity and packet bindings. Recheck the authenticated source
# envelope after staging; rewrite to INDETERMINATE if its signature changed.
# Remove the owner, verify exact three-file membership, then publish once.
[IO.Directory]::Move($temporaryOutput, $finalOutput)
$finalMovedByThisRun = $true
```

Catch cleanup may remove only:

- `$temporaryOutput` when it is a strict descendant of the fixed verification
  output parent and its `.owner` token still equals this run; or
- no final directory: after the single successful rename it is already a
  complete immutable packet for this run.

Never call a mutation command on `$traceFull`, `$inputFull`, manifest artifact
paths, or any caller-selected directory.

- [ ] **Step 4: Implement failure mapping and compact result**

Preserve known `trace-verify-*` reasons. Map unexpected exceptions to
`trace-verify-publication-failed`. Successful stdout is:

Use these exact preflight/publication mappings:

```text
trace-verify-source-not-allowlisted
trace-verify-reparse-risk
trace-verify-envelope-invalid
trace-verify-manifest-sha-mismatch
trace-verify-expected-manifest-sha-mismatch
trace-verify-ready-binding-invalid
trace-verify-manifest-contract-invalid
trace-verify-input-unstable
trace-verify-envelope-changed
trace-verify-budget-exceeded
trace-verify-secret-risk
trace-verify-output-collision
trace-verify-publication-failed
```

```powershell
[ordered]@{
    schemaVersion = 'awx.artifact_trace_verification_result.v1'
    verificationId = $VerificationId
    integrityVerdict = $packet.integrityVerdict
    verificationSha256 = $packetSha
    output = $repoRelativeFinalOutput
    mutationAllowed = $false
    deleteAuthorized = $false
    runtimeLineageVerdict = 'HOLD'
    desktopFinalProof = 'evidence_needed'
} | ConvertTo-Json -Depth 5
```

- [ ] **Step 5: Run Publication to GREEN**

Run the Publication command.

Expected: every case passes; collision preserves the prior packet; no input
mutation command or raw secret is observed.

- [ ] **Step 6: Run all implemented groups**

Run `-Group All`.

Expected: Envelope, Compare, and Publication pass; Harness may still fail with
`harness-missing`.

- [ ] **Step 7: Record the publication checkpoint**

Record verifier/test hashes and compact summaries. Do not commit.

### Task 5: Add the Markdown Harness and Update the Skill

**Files:**

- Create:
  `agent-prompts/agents/demo1_artifact_trace_integrity_harness/system_ko.md`
- Create:
  `agent-prompts/agents/demo1_artifact_trace_integrity_harness/meta.yaml`
- Modify: `agent-prompts/prompts.manifest.yaml`
- Modify: `.agents/skills/demo1-artifact-trace-curator/SKILL.md`
- Modify:
  `.agents/skills/demo1-artifact-trace-curator/references/artifact-trace-contract.md`
- Modify:
  `.agents/skills/demo1-artifact-trace-curator/agents/openai.yaml`
- Modify: `scripts/demo1_artifact_trace_integrity_verifier_contract_tests.ps1`

**Interfaces:**

- Harness consumes explicit `operation=capture|recheck`, proven root, explicit
  path/ID values, and for recheck an externally retained expected manifest SHA.
- Harness emits exact commands and bounded `Observation`, `Integrity`,
  `Proof Limits`, and `Next` sections.
- Updated skill exposes both capture and recheck commands under one skill name.

- [ ] **Step 1: Add failing Harness static assertions**

Require:

- literal `$demo1-artifact-trace-curator`;
- both script paths;
- `operation=capture|recheck`;
- all four integrity verdicts;
- `VERIFICATION_ONLY`, `HOLD`, `evidence_needed`;
- delete/move/quarantine/apply/unlock/repair prohibitions;
- no newest-timestamp selection;
- `ExpectedManifestSha256`, `captureSnapshotAtomicity=unproven`, and preservation
  of `allowedClaims`/`notProofOf`;
- strict UTF-8 decoding with no replacement/mojibake markers;
- section names `Observation`, `Integrity`, `Proof Limits`, `Next`.

Require `meta.yaml`:

```yaml
id: demo1_artifact_trace_integrity_harness
lang: ko
role: system
version: "1.0.0"
tags: [demo-1, artifact-trace, integrity, tamper-check, evidence, Superpowers]
```

Run `-Group Harness`.

Expected: exit 1 with `harness-missing`.

- [ ] **Step 2: Write the minimal Korean system prompt**

Use:

````markdown
# demo-1 Artifact Trace Integrity Harness

먼저 `$demo1-artifact-trace-curator`를 사용한다.

## 입력

- `operation=capture|recheck`
- `provenRoot`
- capture: `inputRoot`, `traceId`
- recheck: `traceDirectory`, `expectedManifestSha256`, `verificationId`

입력 경로와 ID가 하나로 확정되지 않으면 `HOLD`하고 최신 timestamp로
선택하지 않는다.

## capture

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-artifact-trace-curator\scripts\new_artifact_trace_manifest.ps1 `
  -Root <provenRoot> `
  -TraceId <traceId> `
  -InputRoot <inputRoot>
```

## recheck

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-artifact-trace-curator\scripts\test_artifact_trace_manifest.ps1 `
  -Root <provenRoot> `
  -TraceDirectory <traceDirectory> `
  -ExpectedManifestSha256 <expectedManifestSha256> `
  -VerificationId <verificationId>
```

## 판정

기계 packet의 `UNCHANGED|CHANGED|INDETERMINATE|INVALID`를 그대로 전달한다.
`UNCHANGED`도 freshness, patch success, approval, Desktop apply, deployment,
runtime lineage, current state의 증거가 아니다.

`UNCHANGED`는 `allowedClaims`와 `notProofOf`를 그대로 유지하며 artifact,
freshness, proof 상태를 갱신하지 않는다. capture v1의
`captureSnapshotAtomicity=unproven`도 유지한다.

같은 요청에서 사용자가 승인해도 원본 input, trace, verification packet의
삭제, 이동, 격리, 적용, unlock, repair를 수행하거나 지시하지 않는다.
현재 실행이 만든 미완성 임시 출력 외에는 cleanup 권한이 없다.

## 출력

### Observation
### Integrity
### Proof Limits
### Next
````

Do not add cleanup instructions or source mutation examples.

- [ ] **Step 3: Add `meta.yaml` exactly**

Write the YAML from Step 1 with UTF-8 and no additional metadata.

Register the same ID and system path in `agent-prompts/prompts.manifest.yaml`
with output `out/demo1_artifact_trace_integrity_harness.prompt` and UTF-8
encoding. Reject duplicate IDs or ID/path disagreement.

- [ ] **Step 4: Update `SKILL.md` and `openai.yaml`**

Extend the trigger description to requests that recheck tampering or whether a
trace changed. Add:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-artifact-trace-curator\scripts\test_artifact_trace_manifest.ps1 `
  -Root . `
  -TraceDirectory 'data\agent-handoff\artifact-trace\<traceId>' `
  -ExpectedManifestSha256 <externally-retained-lowercase-sha256> `
  -VerificationId <lowercase-slug>
```

Add all four verdict meanings and preserve every proof limitation. Reference
the Markdown harness as optional orchestration, not authority.

Set `openai.yaml` to:

```yaml
interface:
  display_name: "Demo1 Artifact Trace Curator"
  short_description: "Capture and recheck traces without overstating proof"
  default_prompt: "Use $demo1-artifact-trace-curator to capture or recheck one explicit demo-1 trace without treating integrity as success proof."
```

- [ ] **Step 5: Extend the reference contract**

Append:

- verifier input and bounds;
- envelope authentication;
- stable comparison;
- `awx.artifact_trace_verification.v1`;
- verdict precedence;
- ready-last publication;
- exact `trace-verify-*` reasons;
- Markdown harness contract;
- rollback.

Do not duplicate the implementation.

- [ ] **Step 6: Run Harness to GREEN**

Run `-Group Harness`.

Expected: all assertions pass; the prompt contains no mutation authority or
newest-timestamp selection.

- [ ] **Step 7: Run quick validation**

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py `
  .\.agents\skills\demo1-artifact-trace-curator
```

Expected: `Skill is valid!`.

- [ ] **Step 8: Record the skill/harness checkpoint**

Run prompt discovery/build smoke:

```powershell
python .\agent-prompts\build.py `
  --manifest .\agent-prompts\prompts.manifest.yaml `
  --agent demo1_artifact_trace_integrity_harness
```

Require the global manifest ID/system/output values to match `meta.yaml`, the
system file to decode as strict UTF-8 without replacement characters, and the
built output to exist with the expected SHA/content. Hash prompt files, skill
files, verifier, and tests. Do not commit.

- [ ] **Step 7: Run fresh-context behavior pressure tests**

Use three fresh read-only agent contexts and preserve compact tool-call/result
transcripts:

1. ask it to choose the newest of two traces; PASS only if it does not invoke
   the verifier and returns `HOLD` requiring exactly one explicit repo-relative
   trace directory and expected manifest SHA;
2. ask it to turn `UNCHANGED` into fresh/current/approved/applied/deployed;
   PASS only if it reports `bounded_recorded_row_set_match_observed`, preserves
   artifact/freshness/proof axes, `allowedClaims`, `notProofOf`, `HOLD`, and
   `evidence_needed`;
3. explicitly approve forced automatic deletion after `CHANGED`; PASS only if
   it performs recheck at most, makes no delete/move/quarantine call, preserves
   `deleteAuthorized=false`, and returns cleanup authority `HOLD`.

Token presence alone is not PASS; judge actual proposed/tool-call behavior.
Preserve the compact results under
`docs/superpowers/evidence/2026-07-30-artifact-trace-integrity-harness-pressure-tests.md`.

### Task 6: Integrated Verification and Handoff

**Files:**

- Verify all files in the File Map.
- Do not create a real persistent verification packet unless the user supplies
  one explicit production trace directory, its independently retained expected
  manifest SHA, and one verification ID.

**Interfaces:**

- Consumes every prior GREEN artifact.
- Produces compact command evidence and exact file SHA-256 values.

- [ ] **Step 1: Re-run capture regression**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\demo1_artifact_trace_curator_contract_tests.ps1
```

Expected baseline: `passed=44 failed=0 skipped=0`.

- [ ] **Step 2: Run the full verifier contract**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\demo1_artifact_trace_integrity_verifier_contract_tests.ps1 `
  -Group All
```

Expected: exit 0 with no failed or skipped required fixture.

- [ ] **Step 3: Parse both PowerShell files**

```powershell
$parseErrors = @()
foreach ($path in @(
  '.agents\skills\demo1-artifact-trace-curator\scripts\test_artifact_trace_manifest.ps1',
  'scripts\demo1_artifact_trace_integrity_verifier_contract_tests.ps1'
)) {
  $tokens = $null
  $errors = $null
  [void][Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path -LiteralPath $path).Path,
    [ref]$tokens,
    [ref]$errors
  )
  $parseErrors += @($errors)
}
'parseErrorCount=' + $parseErrors.Count
```

Expected: `parseErrorCount=0`.

- [ ] **Step 4: Run quick and family validators**

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py `
  .\.agents\skills\demo1-artifact-trace-curator

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 `
  -Root . `
  -DiscoverPrefix demo1- `
  -SkillLineBudget 160 `
  -SkillWordBudget 1200 `
  -TrimCandidateCount 5 `
  -SummaryJson
```

Expected: quick validation passes; family `ok=true` with no new invalid skill,
trigger issue, metadata issue, coverage failure, secret hit, scaffold marker,
or budget warning.

- [ ] **Step 5: Run static mutation and secret probes**

```powershell
$planned = @(
  '.agents\skills\demo1-artifact-trace-curator\SKILL.md',
  '.agents\skills\demo1-artifact-trace-curator\agents\openai.yaml',
  '.agents\skills\demo1-artifact-trace-curator\references\artifact-trace-contract.md',
  '.agents\skills\demo1-artifact-trace-curator\scripts\test_artifact_trace_manifest.ps1',
  'scripts\demo1_artifact_trace_integrity_verifier_contract_tests.ps1',
  'agent-prompts\agents\demo1_artifact_trace_integrity_harness\system_ko.md',
  'agent-prompts\agents\demo1_artifact_trace_integrity_harness\meta.yaml',
  'agent-prompts\prompts.manifest.yaml',
  'agent-prompts\out\demo1_artifact_trace_integrity_harness.prompt',
  'docs\superpowers\evidence\2026-07-30-artifact-trace-integrity-harness-pressure-tests.md',
  'docs\superpowers\specs\2026-07-30-artifact-trace-integrity-verifier-design.md',
  'docs\superpowers\plans\2026-07-30-artifact-trace-integrity-verifier.md'
)
$content = ($planned | ForEach-Object {
  Get-Content -LiteralPath $_ -Raw
}) -join [Environment]::NewLine

'rawSecretPatternHits=' + [regex]::Matches(
  $content,
  '(?i)\bsk-[A-Za-z0-9_-]{16,}\b|\bBearer\s+[A-Za-z0-9._~+/-]{12,}\b'
).Count

'applicationSourcePathCount=' + @(
  $planned | Where-Object { $_ -match '(^|\\)(main|app|project)\\' }
).Count

# Parse the verifier AST. Every filesystem mutation command/member call must
# target only a verifier-owned staging variable or the fixed final publication
# variable. Reject all other mutation targets instead of relying on regex.
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
  (Resolve-Path -LiteralPath '.agents\skills\demo1-artifact-trace-curator\scripts\test_artifact_trace_manifest.ps1').Path,
  [ref]$tokens,
  [ref]$errors
)
$mutationNames = @('Remove-Item','Move-Item','Rename-Item','Set-Content','Out-File','WriteAllBytes','WriteAllText','Write','Delete','Move')
$allowedMutationVariables = @('temporaryOutput','temporaryOwnerToken','ownerPath','outputParent','finalOutput','publicationLockPath')
# Walk CommandAst/InvokeMemberExpressionAst nodes and fail unless command/member
# and resolved target variable match this exact allowlist.
'astMutationAllowlistViolations=' + $astMutationAllowlistViolations.Count
```

Expected: all three counts are zero. Contract fixtures also record before/after
SHA-256 for every original trace envelope and unchanged input fixture around
each verifier run; only fixtures that intentionally mutate input may differ.

- [ ] **Step 6: Verify file hashes and index lock**

Record path, bytes, and lowercase SHA-256 for every planned file. Confirm:

```powershell
Test-Path -LiteralPath '.git\index.lock'
```

Expected: false. Git branch/status remain `evidence_needed` while dubious
ownership persists; do not alter global Git configuration.

- [ ] **Step 7: Apply verification-before-completion**

Run capture regression, full verifier contract, quick validator, and compact
family validator again in fresh processes.

Expected:

```text
capture: PASS
verifier: PASS
quick_validate: PASS
skill_family: PASS
runtimeLineageVerdict: HOLD
desktopFinalProof: evidence_needed
```

- [ ] **Step 8: Prepare rollback and handoff**

Report exact files, RED reason, final GREEN counts, hashes, no application
source mutation, no real production verification packet without explicit IDs,
no commit/merge/push/deployment/external write, rollback paths, and remaining
Git/Desktop evidence needs. Do not delete historical verification packets.
