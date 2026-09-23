# Artifact Trace Curator Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Repository instructions prohibit sub-agent delegation for this session, so execute the tasks inline and keep each checkpoint evidence-backed.

**Goal:** Add one bounded, read-only repo-local skill that inventories leftover files under explicit `data/agent-handoff/**` or `__patch_drop__/**` subroots without treating file existence as proof of success, approval, deployment, runtime lineage, or current state.

**Architecture:** A PowerShell manifest generator resolves one explicit repository root and one explicit allowlisted input subroot, rejects reparse traversal and budget violations, classifies known trace schemas and PatchDrop bundle parts, and atomically publishes an inventory-only manifest plus SHA sidecar and final ready marker. A concise skill delegates mutation to existing patch/session/janitor owners and documents the proof vocabulary; a repository contract test builds temporary fixtures and checks positive and fail-closed paths.

**Tech Stack:** PowerShell 5.1-compatible scripting, JSON, SHA-256, repo-local Codex skill metadata, Python skill validators.

---

## Fixed boundaries

- Allowed read roots: one caller-selected descendant of `data/agent-handoff/` or `__patch_drop__/`.
- Fixed output root: `data/agent-handoff/artifact-trace/<traceId>/`.
- Mutation surface: only the new trace output directory during generator execution.
- Forbidden actions: deleting, moving, quarantining, applying, rolling back, unlocking, or editing inventoried artifacts.
- Fixed top-level verdicts: `INVENTORY_ONLY`, `mutationAllowed=false`, `deleteAuthorized=false`, `runtimeLineageVerdict=HOLD`, `desktopFinalProof=evidence_needed`.
- File lifecycle and proof status remain separate fields.
- Existing MacSrc direct-patch, patch postprocessor, and PatchDrop janitor skills retain mutation and terminal-adjudication ownership.
- No application source, public API, database, credential, environment-variable, or provider behavior changes.

### Task 1: Establish RED contract coverage

**Files:**

- Create: `scripts/demo1_artifact_trace_curator_contract_tests.ps1`
- Expected missing implementation: `.agents/skills/demo1-artifact-trace-curator/scripts/new_artifact_trace_manifest.ps1`

**Step 1: Write the contract harness**

Create temp-only fixtures outside the repository and invoke the expected generator with an explicit temporary repository root. Cover:

1. tombstone classification is `cleanup_only` and never proof of patch/runtime/deployment/approval/current state;
2. completion without matching session and verification siblings is `proofStatus=invalid`;
3. matching session, verification, and completion hashes produce `proofStatus=structurally_bound`, `proofScope=source_patch`, never runtime proof;
4. PatchDrop ready marker without all v3 parts is invalid;
5. complete v3 producer bundle is limited to `patch_handoff` and does not imply Desktop apply;
6. unknown diagnostics expose metadata and hash only, never raw content;
7. freshness is independent of proof status;
8. reparse traversal, file/byte/JSON/time/output limits, secret risk, and output collision fail closed;
9. successful output contains manifest, matching SHA sidecar, and a ready marker published last;
10. all test fixtures and generated temporary roots are removed in `finally`.

The harness must print compact pass/fail counts and reason codes, not fixture bodies.

**Step 2: Run RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_artifact_trace_curator_contract_tests.ps1
```

Expected: non-zero exit because the generator is absent. Preserve only the concise missing-implementation failure as RED evidence.

### Task 2: Scaffold the repo-local skill

**Files:**

- Create: `.agents/skills/demo1-artifact-trace-curator/SKILL.md`
- Create: `.agents/skills/demo1-artifact-trace-curator/agents/openai.yaml`
- Create directory: `.agents/skills/demo1-artifact-trace-curator/references/`
- Create directory: `.agents/skills/demo1-artifact-trace-curator/scripts/`

**Step 1: Use the canonical skill scaffold generator**

Run:

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\init_skill.py demo1-artifact-trace-curator --path .\.agents\skills --resources scripts,references --interface display_name="Demo1 Artifact Trace Curator" --interface short_description="Classify leftover traces without overstating proof" --interface default_prompt="Use `$demo1-artifact-trace-curator to inventory an explicit demo-1 trace root without treating files as success proof."
```

Expected: the new skill directory is created once; no existing skill is overwritten.

**Step 2: Replace scaffold text with the approved contract**

`SKILL.md` must include trigger and non-trigger, owner and mutation surface, input/output schema, bounds, redaction, failure classifications, fail-closed behavior, rollback, reuse rationale, and falsifying tests. Keep it within the repository family line/word budgets.

### Task 3: Implement the manifest generator

**Files:**

- Create: `.agents/skills/demo1-artifact-trace-curator/scripts/new_artifact_trace_manifest.ps1`
- Create: `.agents/skills/demo1-artifact-trace-curator/references/artifact-trace-contract.md`

**Step 1: Implement validated inputs and containment**

Parameters:

- mandatory `Root`, `TraceId`, `InputRoot`;
- bounded defaults `MaxFiles=512`, `MaxTotalMiB=64`, `MaxJsonMiB=2`, `TimeoutSeconds=120`, `MaxOutputMiB=1`.

Resolve canonical paths, require `TraceId` to match a conservative slug pattern, require `InputRoot` to be a strict descendant of an allowlisted root, reject output-as-input, reject any reparse point in the path or inventory, and reject an existing final output directory.

**Step 2: Build a stable, bounded inventory**

Sort by repository-relative path. Record only path, role/state, size, SHA-256, last-write UTC, freshness, proof fields, allowed claims, forbidden claims, retention class, and `deleteAuthorized=false`. Do not emit file content. Stop on file count, total bytes, JSON parse size, elapsed time, or output size violations.

**Step 3: Classify recognized schemas without inventing truth**

- `awx.macsrc_smb_patch_lease_cleanup.v1`: `artifactRole=tombstone`, `proofScope=cleanup_only`; explicitly deny patch/runtime/deployment/approval/current-state claims.
- Completion evidence: require matching session and verification siblings, declared SHA-256 links, matching run/session identifiers, and equal target postimage hashes. Otherwise invalid. A valid binding proves only `source_patch` structure.
- PatchDrop v3: require `.patch`, `.report.md`, `.verify.log`, `.sha256.txt`, `.manifest.json`, checksum consistency, and source-isolation PASS. A ready marker without them is invalid. A valid producer bundle proves only `patch_handoff`, never Desktop apply.
- Unknown files: metadata-only diagnostics with `proofScope=none`; no raw body or inferred semantics.

**Step 4: Publish atomically**

Write to a sibling temporary directory, serialize `awx.artifact_trace_manifest.v1`, reject secret-like material in the would-be JSON, enforce the output budget, write `manifest.json`, write a matching `manifest.sha256`, rename the temporary directory to the final trace directory, and create `.ready` last. Clean temporary output on every failure.

**Step 5: Document schemas and reason codes**

The reference must list exact fields, allowed enums, proof rules, and fail-closed reason codes such as `trace-input-not-allowlisted`, `trace-reparse-risk`, `trace-budget-exceeded`, `trace-json-too-large`, `trace-secret-risk`, `trace-output-collision`, `trace-binding-invalid`, and `trace-publication-failed`.

### Task 4: Add narrow ownership cross-references

**Files:**

- Modify: `.agents/skills/demo1-macsrc-smb-direct-patch/SKILL.md`
- Modify: `.agents/skills/demo1-macsrc-patch-postprocessor/SKILL.md`
- Modify: `.agents/skills/demo1-skill-family-postprocessor/SKILL.md`

**Step 1: Add one concise routing note per owner**

- Direct-patch skill: route leftover trace interpretation to `$demo1-artifact-trace-curator`; do not weaken lease cleanup ownership or safety gates.
- Patch postprocessor: route non-mutating historical trace inventory to the curator; keep terminal adjudication here.
- Skill family postprocessor: include the curator in family validation and keep validation ownership here.

No application source or unrelated skill text changes.

### Task 5: Drive RED to GREEN and validate the skill family

**Step 1: Run the focused contract tests**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_artifact_trace_curator_contract_tests.ps1
```

Expected: exit 0; every named fixture passes; temp cleanup passes.

**Step 2: Run canonical skill validation**

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .\.agents\skills\demo1-artifact-trace-curator
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 -TrimCandidateCount 5 -SummaryJson
```

Expected: the new skill passes quick validation; family validation introduces no curator-specific failures.

**Step 3: Run static safety probes**

Confirm:

- only the planned files changed by comparing recorded pre/post SHA-256 values;
- no application source was changed;
- no raw secret values or placeholder markers appear;
- the generator contains no deletion/move/quarantine/apply operation against input artifacts;
- every manifest path is relative and every proof claim is bounded.

**Step 4: Re-run verification before completion**

Run the focused contract test and quick validator again from fresh command invocations. Record command, exit code, pass/fail counts, and hashes. Git branch/status remain `evidence_needed` if dubious ownership persists; do not change global trust settings.

### Task 6: Handoff and rollback

Summarize actual changes and evidence. State that runtime lineage and Desktop final application proof remain HOLD/evidence-needed because this skill only inventories traces. Rollback consists of removing the new skill, contract test, plan/spec if requested, and the three narrow cross-references; generated trace manifests are historical artifacts and must not be silently deleted by rollback.
