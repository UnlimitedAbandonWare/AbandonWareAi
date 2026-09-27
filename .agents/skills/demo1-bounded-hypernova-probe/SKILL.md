---
name: demo1-bounded-hypernova-probe
description: Use when demo-1 HYPERNOVA, TWPM, CVaR, Risk-K, BodeClamp
---

# Demo1 Bounded HYPERNOVA Probe

## Core Rule

Treat every tuning value as a claim, not truth. Change a HYPERNOVA parameter
only after a deterministic bounded probe produces finite, redacted, repeatable
Desktop evidence. Treat 5-9 hours as an evidence budget, never as an automatic
success threshold.

## Authority And Scope

Use this order:

1. Current Desktop source and command output.
2. Root `AGENTS.md`, active repo-local skills, and registered prompts.
3. The current goal or attachment.
4. Official documentation for changing external APIs.
5. Prior reports, producer evidence, and memory.

Confirm canonical owners before editing:

- TWPM: `TailWeightedPowerMeanFuser`
- fusion and applied bounds: `NovaNextFusionService`
- CVaR: `CvarAggregator`
- Risk-K: `RiskKAllocator` and its active implementation
- output clamp: `BodeClamp`
- configuration: `NovaNextProperties`, `application-nova-next.yml`,
  and `plans/hyper_nova.v1.yaml`

Patch only active sourceSets. Do not edit Notebook or Mac mini source,
inactive mirrors, `apikey*`, `.env*`, `openssl`/`opnessl`, env values,
generated output, or another worker's dirty HYPERNOVA files.

## Desktop Intake

Run from `C:\AbandonWare\demo-1\demo-1\src`:

```powershell
$indexOp = (Test-Path ".git\MERGE_HEAD") -or (Test-Path ".git\CHERRY_PICK_HEAD") -or (Test-Path ".git\rebase-merge") -or (Test-Path ".git\rebase-apply")
if ($indexOp) { throw "index-lock-conflict" }
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
'{"nodeRole":"desktop","root":".","requestId":"bounded-hypernova-source-scan","sessionId":"bounded-hypernova"}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - source_scan
$targets = 'main/java/com/nova/protocol/fusion/*','main/java/com/nova/protocol/alloc/*','main/java/com/nova/protocol/properties/*','main/java/com/example/lms/service/rag/fusion/*','main/java/com/example/lms/service/agent/*','src/test/java/com/nova/protocol/*','src/test/java/com/example/lms/service/agent/*','main/resources/application-nova-next.yml','main/resources/plans/hyper_nova.v1.yaml'
Get-FileHash -Algorithm SHA256 -Path $targets -ErrorAction SilentlyContinue | Format-Table Path,Hash
```

Stop source edits for `index-lock-conflict`, a pending top-level PatchDrop
patch, unproven active sourceSet, or unsafe overlap. A producer-pending notice
without a top-level patch is supporting evidence, not Desktop ownership.

## Parameter Evidence Ledger

Record one row per candidate:

| Field | Required evidence |
| --- | --- |
| parameter | Exact property, field, or method argument |
| raw configured | Redacted numeric value and source location |
| applied value | Runtime/test value after finite checks and bounds |
| valid band | Bound proved by current source, not copied from an old prompt |
| output invariant | Finite result and expected score/allocation range |
| trace | Allowlisted key proving requested versus applied behavior |
| falsifier | Focused test or probe command that can fail the claim |

Required H03 evidence includes `hypernova.twpmP`,
`hypernova.cvarPhi`, and `hypernova.clampApplied`. When the live source
owns them, also verify `hypernova.twpmP.max`,
`hypernova.twpmP.maxBounded`, `hypernova.cvarFusedScore`, and
`hypernova.riskKAlloc`. Do not synthesize missing trace values.

## Bounded Probe Contract

Prefer an existing unit test or admin-only, fail-closed probe. Add a RED test
before behavior changes. Cover a finite candidate set:

- below the supported band,
- the current default,
- the current upper edge,
- above the upper edge,
- non-finite input when the binding/API can represent it.

Assert all of the following:

- the applied value is inside the source-owned band;
- final scores are finite and within their owned range;
- Risk-K allocations are nonnegative and respect current total/floor caps;
- the trace exposes applied values, bounds, and whether clamping occurred;
- trace/API output contains no raw query, prompt, secret, env value, or header;
- the original input/config remains unchanged during the probe.

Do not create an adaptive tuner, random search, production traffic experiment,
or external provider call for this pass.

## 9-Hour Evidence Loop

1. Use up to two hours for source-only owner, caller, config, test, and trace
   reconnaissance. End early when evidence is decisive.
2. Establish the baseline with one focused probe and save only bounded
   counts/values and artifact paths.
3. Select one hypothesis with an exact falsifier.
4. Run RED, patch the smallest clean seam, then run GREEN.
5. Broaden verification only after focused proof passes.
6. Stop when the failure class changes, the next file overlaps unrelated work,
   or the live source already satisfies the contract.

Return `no_patch_needed` when current source and fresh tests already prove the
requested bound. Return `evidence_needed: <artifact> / verify with <command>`
when a value lacks an owner, unit, bound, trace, or verifier.

## Safe Patch And Rollback

For every file, state the before intent, after intent, why that file alone owns
the gap, and the rollback. Preserve a minimal unified diff. Never use
`git reset --hard` or broad checkout in a dirty tree. Roll back only the
lines added by this pass with a reviewed reverse patch.

Use one primary failure class:

`worktree-overlap`, `wrong-sourceset`, `secret-leak-risk`,
`prompt-rule-violation`, `gradle-cache-collision`,
`external-evidence-overrequired`, `bounded-probe-missing`,
`non-finite-output`, or `other`.

## Desktop Verification

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop-bounded-hypernova"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop-bounded-hypernova"

.\gradlew.bat test --tests "*NovaNextFusion*" --tests "*TailWeighted*" --tests "*CvarAgg*" --tests "*RiskK*" --tests "*ReportSnapshotServiceTest*" --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

Run boot, Browser, or Computer proof only when the changed surface requires it.
Keep Supabase read-only and `evidence_needed` until project-scoped auth is
proved. Keep Superpowers as process support; repo evidence remains authoritative.

## Report Contract

Use exactly these six top-level headings without insertion or reordering:
`요약`, `do01 / Observation`, `do02 / Patch Blocks`,
`do03 / Setup Commands`, `do04 / Verification`, `do05 / Risks & Next Steps`.
Put the parameter ledger in do01 and probe/test results in do04. Include
Browser/Computer/Supabase lane status, rollback notes, one
counterexample, confidence, and one next action. Never claim build, boot,
provider, or external proof without current output.
