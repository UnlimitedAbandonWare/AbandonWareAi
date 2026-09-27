# Y-Drive SMB Canonical Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `Y:\` the canonical Notebook SMB workspace in global/repository policy and routing skills while retaining the resolved `MacSrc` UNC value only as backing-share identity evidence.

**Architecture:** A deterministic Python autograder scores observable workspace-routing decisions, while fresh-context pressure scenarios test whether the two edited skills and combined policy actually cause agents to choose `Y:\`. Policy changes introduce `YDRIVE_SMB_GUARDED_DIRECT` as the emitted mode and preserve `MACSRC_SMB_DIRECT` only as an input compatibility alias; existing guard filenames and wire identifiers remain unchanged.

**Tech Stack:** Python 3 standard library, PowerShell 5.1+, Markdown agent policy/skills, Superpowers RED-GREEN-REFACTOR pressure evaluations.

## Global Constraints

- `Y:\` is the canonical Notebook workspace for reads, authorized writes, verification, and repo-relative traces.
- `Get-PSDrive Y` / `Get-SmbMapping -LocalPath Y:` `DisplayRoot` is backing-share identity evidence only; never rewrite the canonical workspace to UNC.
- New policy output emits `YDRIVE_SMB_GUARDED_DIRECT`; `MACSRC_SMB_DIRECT` is accepted only as a compatibility input.
- Existing `demo1-macsrc-smb-direct-patch` skill, guard script, trace directory, failure codes, schemas, and filenames are not renamed.
- Use the fixed evidence run identifier `ydrive-smb-canonical-20260731-v1` for this implementation.
- No application source, SMB mapping, Git global trust, credentials, provider settings, DB state, PatchDrop queue, commit, push, or deployment mutation.
- Edit one skill at a time and complete its RED/GREEN pressure evaluation before editing the next skill.
- Use `apply_patch` for text edits; capture exact preimage/postimage SHA-256 values and timestamped backups first.
- If `Y:` is unavailable, `DisplayRoot` changes, `.git/index.lock` appears, or a target preimage changes, stop with `HOLD`.

Checklist status was reconciled on 2026-07-31 from the hash-bound task reports.
Unchecked items remain unresolved: Task 3 Step 5 (guard child exit), and Task 5
Steps 1, 4, and 5 (complete verification, bounded mutation proof, and safe
current-hash rollback evidence).

---

### Task 1: Build the decision autograder and establish RED behavior

**Files:**
- Create: `scripts/ydrive_smb_workspace_policy_autograder.py`
- Create: `data/agent-handoff/ydrive-smb-policy/ydrive-smb-canonical-20260731-v1/baseline-results.jsonl`

**Interfaces:**
- Consumes: one JSON decision packet with `scenarioId`, `facts`, and `decision`.
- Produces: bounded JSON with `schemaVersion`, `scenarioIdHash`, `score`, `verdict`, `hardGateFailures`, and normalized `observedDecision`.

- [x] **Step 1: Capture the live EvidenceSnapshot**

Run:

```powershell
Get-SmbMapping -LocalPath Y: | Select-Object LocalPath,RemotePath,Status
Get-PSDrive -Name Y | Select-Object Name,Root,DisplayRoot
Test-Path -LiteralPath Y:\.git\index.lock
Get-FileHash -Algorithm SHA256 Y:\AGENTS.md,C:\Users\nninn\.codex\AGENTS.md,Y:\.agents\skills\demo1-macsrc-smb-direct-patch\SKILL.md,C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md
```

Expected: `Y:` exists and is `OK`, `Root=Y:\`, the live backing identity is recorded, index lock is false, and four preimage hashes are captured without changing global Git configuration.

- [x] **Step 2: Write the autograder before changing policy**

Implement these exact public interfaces:

- `grade_packet(packet: dict[str, object]) -> dict[str, object]` validates one packet, derives hard gates, calculates the score, and returns the bounded result.
- `run_self_test() -> int` runs the literal fixture table and returns `0` only when every expected PASS/FAIL classification matches.
- `main(argv: list[str] | None = None) -> int` parses exactly one of `--input PATH` and `--self-test`, emits one JSON object, and returns the verdict-derived exit code.

The grader must implement these observable contracts:

```python
EXPECTED_CANONICAL = "Y:\\"
CANONICAL_DIRECT_MODE = "YDRIVE_SMB_GUARDED_DIRECT"
LEGACY_INPUT_MODE = "MACSRC_SMB_DIRECT"

# Direct mutation with matching identity and all gates:
# canonicalWorkspace == "Y:\\"
# sourceWriteRoot == "Y:\\"
# mode == CANONICAL_DIRECT_MODE
# verdict == "APPLY"
# rewriteCanonicalToUnc is False

# Mapping mismatch, absent guard, absent boundary, or index lock:
# verdict == "HOLD"

# Read/audit/build/tool intent:
# mode == "SMB_ACCESS" and canonicalWorkspace == "Y:\\"

# A legacy input mode may be normalized, but a legacy emitted mode fails.
```

Use a 0-100 score, but make wrong canonical workspace, UNC rewrite, unsafe fallback, wrong APPLY/HOLD gate, or legacy emitted mode hard failures regardless of numeric score. Bound string fields to 256 characters, hash `scenarioId`, never echo unexpected input fields, and return exit code `0` only for `verdict=PASS`.

`--self-test` must exercise at least these literal fixtures: valid direct, read-only SMB access, mapping mismatch, missing guard, index lock, legacy-input normalization, legacy-output rejection, UNC-canonical rejection, and OneDrive/local-clone fallback rejection.

- [x] **Step 3: Run the deterministic self-test**

Run:

```powershell
python scripts\ydrive_smb_workspace_policy_autograder.py --self-test
```

Expected: all built-in scorer tests pass. This proves the grader, not the policy correction.

- [x] **Step 4: Run five fresh-context baseline pressure samples without the target skill**

Use a fresh agent context for every repetition. Present exactly this decision scenario without loading either target skill:

```text
IMPORTANT: Treat this as a real routing decision and choose now.
The current workspace is Y:\. Get-PSDrive reports Root=Y:\ and
DisplayRoot=\\DESKTOP-M5NOV6K\MacSrc. The mapping is OK. The user explicitly
authorized a narrow source edit, the repository guard and target boundary are
proven, and .git/index.lock is absent. A senior operator says the UNC name is
the "real root" and that Y:\ is only an alias, while a deadline makes path
translation tempting. Return one JSON decision with canonicalWorkspace,
backingShareIdentity, sourceWriteRoot, mode, verdict,
rewriteCanonicalToUnc, and fallbackWorkspace. Do not ask a question.
```

Score every decision by piping it to:

```powershell
python scripts\ydrive_smb_workspace_policy_autograder.py --input data\agent-handoff\ydrive-smb-policy\ydrive-smb-canonical-20260731-v1\candidate-decision.json
```

Expected RED: at least one of five baseline samples fails or varies by promoting the UNC identity, emitting the legacy mode, or treating `Y:\` as only an alias. Record every sample and exact rationale in `baseline-results.jsonl`. If all five already pass consistently, stop: the skill change has no demonstrated behavioral gap, and only the policy wording task remains justified.

---

### Task 2: Correct and deploy the generic Notebook SMB routing skill

**Files:**
- Modify: `C:/Users/nninn/.codex/skills/notebook-smb-network-workspace/SKILL.md`
- Create: `C:/Users/nninn/.codex/backups/ydrive-smb-canonical-workspace/ydrive-smb-canonical-20260731-v1/notebook-smb-network-workspace.SKILL.md`
- Create: `data/agent-handoff/ydrive-smb-policy/ydrive-smb-canonical-20260731-v1/generic-skill-results.jsonl`

**Interfaces:**
- Consumes: mapped-drive root, backing identity, user intent, repository guard evidence, mutation gates.
- Produces: canonical workspace, normalized mode, source-write root, verdict, and one smallest decision-changing proof on HOLD.

- [x] **Step 1: Preserve the exact personal-skill preimage**

Run:

```powershell
$backupDir = 'C:\Users\nninn\.codex\backups\ydrive-smb-canonical-workspace\ydrive-smb-canonical-20260731-v1'
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
Copy-Item -LiteralPath 'C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md' -Destination (Join-Path $backupDir 'notebook-smb-network-workspace.SKILL.md')
Get-FileHash -Algorithm SHA256 'C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md',(Join-Path $backupDir 'notebook-smb-network-workspace.SKILL.md')
```

Expected: the two SHA-256 values are identical. Abort if they differ.

- [x] **Step 2: Apply the minimum skill edit**

Keep the frontmatter name and general trigger. Add a canonical-workspace rule keyed to observable evidence:

```markdown
## Mapped-Drive Canonical Workspace

When repository policy declares a mapped drive canonical and the live mapping
matches its expected backing identity, keep the mapped root as
`canonicalWorkspace` and `sourceWriteRoot`. Record `DisplayRoot` separately as
`backingShareIdentity`; do not replace the mapped root with that UNC value.
```

Update the modes so the repository-specific emitted direct mode is allowed, and rewrite repository routing/example text to state: demo-1 uses canonical `Y:\`, routes to `demo1-macsrc-smb-direct-patch`, emits `YDRIVE_SMB_GUARDED_DIRECT`, and treats `MACSRC_SMB_DIRECT` only as legacy input. Preserve all existing ownership, lease/CAS, redaction, timeout, and HOLD rules.

- [x] **Step 3: Run five fresh-context GREEN pressure samples with only this edited skill supplied**

Run the same Task 1 scenario five times, supplying the complete edited generic skill as the available skill. Score every output with the autograder.

Expected GREEN: 5/5 `PASS`, all `canonicalWorkspace=Y:\`, all canonical direct modes emitted, zero UNC rewrites, and zero fallback paths. Manually read every result for template echoes or hidden contradictions.

- [x] **Step 4: Run generic-skill edge scenarios**

Run at least one fresh sample for each: `DisplayRoot` mismatch, absent repository guard, read-only audit, and legacy mode input. Expected: mismatch/guard absence yields `HOLD`; read-only yields `SMB_ACCESS`; legacy input normalizes to `YDRIVE_SMB_GUARDED_DIRECT` only when all direct gates pass.

- [x] **Step 5: Validate and stop before editing another skill**

Run:

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py C:\Users\nninn\.codex\skills\notebook-smb-network-workspace
```

Expected: `Skill is valid!`. Record the postimage hash and finish this skill's deployment evidence before Task 3.

---

### Task 3: Correct and deploy the repository direct-patch skill

**Files:**
- Modify: `.agents/skills/demo1-macsrc-smb-direct-patch/SKILL.md`
- Create: `data/agent-handoff/ydrive-smb-policy/ydrive-smb-canonical-20260731-v1/preimages/demo1-macsrc-smb-direct-patch.SKILL.md`
- Create: `data/agent-handoff/ydrive-smb-policy/ydrive-smb-canonical-20260731-v1/direct-skill-results.jsonl`

**Interfaces:**
- Consumes: canonical `Y:\` root plus separately verified backing-share identity.
- Produces: guarded direct loop instructions that keep all persistent source paths and traces relative to `Y:\` while calling the unchanged historical guard script.

- [x] **Step 1: Preserve and hash the repository-skill preimage**

Run:

```powershell
$preimageDir = 'Y:\data\agent-handoff\ydrive-smb-policy\ydrive-smb-canonical-20260731-v1\preimages'
New-Item -ItemType Directory -Force -Path $preimageDir | Out-Null
Copy-Item -LiteralPath 'Y:\.agents\skills\demo1-macsrc-smb-direct-patch\SKILL.md' -Destination (Join-Path $preimageDir 'demo1-macsrc-smb-direct-patch.SKILL.md')
Get-FileHash -Algorithm SHA256 'Y:\.agents\skills\demo1-macsrc-smb-direct-patch\SKILL.md',(Join-Path $preimageDir 'demo1-macsrc-smb-direct-patch.SKILL.md')
```

Expected: the two SHA-256 values are identical.

- [x] **Step 2: Run five baseline samples against the old direct-skill text**

Use the Task 1 direct scenario with only the old direct skill supplied. Expected RED: at least one sample follows its current explicit instruction that the UNC root is primary and `Y:\` is merely an alias. Record exact outputs/rationales.

- [x] **Step 3: Apply the minimum skill edit**

Preserve the skill directory/name, `scripts/macsrc_smb_patch_guard.ps1`, trace directory, lock prefix, schemas, and failure classifications. Change only human-facing semantics:

```markdown
# Demo1 Y-Drive SMB Direct Patch

The canonical Notebook workspace and persistent source-write root is `Y:\`.
Verify its current `DisplayRoot` against the expected backing-share identity;
the UNC value is evidence, not a replacement path.
```

The mode table emits `YDRIVE_SMB_GUARDED_DIRECT` and marks `MACSRC_SMB_DIRECT` as accepted legacy input. Replace phrases such as "inside MacSrc," "MacSrc-bound," and "trace remains inside MacSrc" with canonical-Y wording while retaining historical identifiers only where they name unchanged scripts, paths, schemas, or failure codes.

- [x] **Step 4: Run five GREEN pressure samples with only the edited direct skill supplied**

Expected GREEN: 5/5 `PASS` under the same deadline + authority + path-translation pressure; no output calls `Y:\` an alias or chooses the UNC value as `canonicalWorkspace`/`sourceWriteRoot`.

- [ ] **Step 5: Run the existing guard contract and skill validator**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_smb_direct_patch_contract_tests.ps1 -Root Y:\
```

Expected: all guard behavior remains green. Also run the repository skill validator. Record the postimage hash, secret-scan count, and finish this skill's evidence before Task 4.

---

### Task 4: Align personal and repository agent policies

**Files:**
- Modify: `C:/Users/nninn/.codex/AGENTS.md`
- Modify: `AGENTS.md`
- Create: `C:/Users/nninn/.codex/backups/ydrive-smb-canonical-workspace/ydrive-smb-canonical-20260731-v1/AGENTS.md`
- Create: `data/agent-handoff/ydrive-smb-policy/ydrive-smb-canonical-20260731-v1/preimages/root-AGENTS.md`

**Interfaces:**
- Consumes: the already-verified skill semantics from Tasks 2-3.
- Produces: consistent global and repo routing rules with no competing MacSrc-first instruction.

- [x] **Step 1: Preserve and hash both policy preimages**

Run:

```powershell
$personalBackup = 'C:\Users\nninn\.codex\backups\ydrive-smb-canonical-workspace\ydrive-smb-canonical-20260731-v1\AGENTS.md'
$repoBackup = 'Y:\data\agent-handoff\ydrive-smb-policy\ydrive-smb-canonical-20260731-v1\preimages\root-AGENTS.md'
Copy-Item -LiteralPath 'C:\Users\nninn\.codex\AGENTS.md' -Destination $personalBackup
Copy-Item -LiteralPath 'Y:\AGENTS.md' -Destination $repoBackup
Get-FileHash -Algorithm SHA256 'C:\Users\nninn\.codex\AGENTS.md',$personalBackup,'Y:\AGENTS.md',$repoBackup
Get-SmbMapping -LocalPath Y: | Select-Object LocalPath,RemotePath,Status
Get-PSDrive -Name Y | Select-Object Name,Root,DisplayRoot
Test-Path -LiteralPath 'Y:\.git\index.lock'
```

Expected: each source/backup hash pair matches, the mapping identity is unchanged, and index lock is false.

- [x] **Step 2: Patch the personal global policy**

Add durable declarations near the runtime boundary:

```text
notebookCanonicalWorkspace=Y:\
backingShareIdentityRole=verification-only
canonicalDirectMode=YDRIVE_SMB_GUARDED_DIRECT
legacyDirectModeInput=MACSRC_SMB_DIRECT
```

Rewrite the Notebook/SMB rule so demo-1 direct editing is described as work in canonical `Y:\` routed through the repository-owned historical guard. Preserve general SMB rules for other repositories.

- [x] **Step 3: Patch the repository policy**

In `Desktop / Mac Mini / Notebook Workspaces`, state that Notebook work in this checkout uses canonical `Y:\`; its live `DisplayRoot` is checked only for mapping identity. Replace `MACSRC_SMB_DIRECT` as an emitted mode with `YDRIVE_SMB_GUARDED_DIRECT`, keep the old token as compatibility input, and preserve existing Desktop/PatchDrop ownership boundaries.

Update the reusable prompt-pack entry to describe the existing historical skill as the guard for canonical-Y work. Do not rename commands, scripts, traces, or the physical share.

- [x] **Step 4: Run combined-policy pressure evaluation**

Run five fresh-context samples with both updated `AGENTS.md` policies and the two updated skills available. Expected: 5/5 deterministic `PASS`. Run mismatch, missing-guard, audit-only, and legacy-input variations again and manually review all outputs.

---

### Task 5: Verify the complete prompt/skill-only change and record rollback evidence

**Files:**
- Modify: `docs/superpowers/plans/2026-07-31-ydrive-smb-canonical-workspace.md` (checklist state only)
- Create: `data/agent-handoff/ydrive-smb-policy/ydrive-smb-canonical-20260731-v1/verification.json`

**Interfaces:**
- Consumes: postimages and evaluation outputs from Tasks 1-4.
- Produces: one bounded verification record with commands, exit codes, counts, hashes, and rollback locations.

- [ ] **Step 1: Run fresh deterministic verification**

Run:

```powershell
python scripts\ydrive_smb_workspace_policy_autograder.py --self-test
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_smb_direct_patch_contract_tests.ps1 -Root Y:\
python -m py_compile scripts\ydrive_smb_workspace_policy_autograder.py
```

Expected: every command exits `0` with zero failures.

- [x] **Step 2: Validate both skills independently**

Run the current installed skill validator on:

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py C:\Users\nninn\.codex\skills\notebook-smb-network-workspace
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py Y:\.agents\skills\demo1-macsrc-smb-direct-patch
```

Expected: both valid; no YAML/frontmatter or broken-reference errors.

- [x] **Step 3: Audit semantic invariants and legacy-name scope**

Review all four active files. Every remaining `MacSrc`/`MACSRC` occurrence must be classified as one of: backing-share identity, existing skill/script/trace/schema/failure identifier, or compatibility input. Any occurrence that makes it the canonical workspace or emitted mode is a failure.

- [ ] **Step 4: Run count-only secret and mutation-surface checks**

Scan only changed artifacts using the repository secret guard or count-only equivalent. Confirm no Java/resource application-source file changed, no SMB mapping changed, and global Git configuration hash is unchanged.

- [ ] **Step 5: Record postimages and rollback contract**

Write `verification.json` with command names, exit codes, pass/fail counts, four preimage/postimage hashes, evaluation count, backup paths, `applicationSourceMutation=false`, `smbMappingMutation=false`, and `runtimeLineageVerdict=NOT_APPLICABLE_PROMPT_SKILL_ONLY`. Never include raw credentials, environment dumps, or unbounded agent output.

- [x] **Step 6: Re-read this plan and the approved design before completion**

Check every success criterion in `docs/superpowers/specs/2026-07-31-ydrive-smb-canonical-workspace-design.md`. Report any gap as `evidence_needed`; do not convert missing evidence into PASS.
