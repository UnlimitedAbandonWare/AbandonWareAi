---
name: quantitative-metric-normalizer
description: Use when demo-1 work involves attached design claims, quantitative scorecards
---

# Quantitative Metric Normalizer

## Purpose

Turn a design narrative into a source-backed metric ledger. Treat design text as a claim map, not truth. Normalize every score so different subsystems can be compared without mixing units, scales, stale evidence, or inactive source.

Use this before asking Antigravity, Codex, Claude, Mac mini, or Notebook agents to judge whether the current Dynamic RAG design is internally broken.

## Hard Rules

- Read current source and command output before trusting the attachment.
- Stay read-only unless the user explicitly asks for a patch pass.
- Do not normalize, rename, or edit `openssl`, `opnessl`, env vars, secret files, or real credentials.
- Keep LangChain4j `1.0.1`, PromptBuilder boundary, sourceSet ownership, and PatchDrop role split as hard gates.
- Report `evidence_needed` when a metric lacks a live owner, unit, timestamp, source, or verifier.
- Never turn a repo-owned `100 / 100` score into a whole-design verdict unless the rubric covers the whole design.

## Workflow

1. Confirm Desktop root and active sourceSets.
   - Root owner: `main/java`, `main/resources`.
   - `:app` owner: `app/src/main/java_clean`, `app/src/main/resources`.
   - Treat archives, `app/src/main/java`, `project/src/main/java`, and generated output as reference unless Gradle proves otherwise.
2. Extract claims from the design into a ledger:
   - subsystem, claimed component, claimed flow, trigger, metric, expected improvement, fail-soft claim.
3. Prove each claim against live source:
   - FQCN exists,
   - caller exists,
   - Spring bean or explicit construction exists,
   - trace/debug key exists,
   - test or verifier exists,
   - output artifact is current.
4. Normalize metrics using one schema:
   - `rawValue`: original value and unit.
   - `direction`: `higher_better`, `lower_better`, `target_band`, or `hard_gate`.
   - `normalized`: `0.0..1.0`.
   - `confidence`: `0.0..1.0`, based on evidence strength and freshness.
   - `weighted`: `normalized * confidence * weight`.
5. Run a combination-breakage matrix:
   - trigger overlap,
   - metric-scale conflict,
   - data-contract mismatch,
   - lifecycle or bean-wiring gap,
   - cancellation/time-budget conflict,
   - fail-soft vs silent-swallow conflict,
   - prompt-boundary or raw evidence leakage,
   - vector dimension or embedding normalization mismatch.
6. Produce only one next action:
   - `verify_only`,
   - `write_antigravity_prompt`,
   - `patch_directive`,
   - `safe_patch_now`,
   - `blocked_evidence_needed`.

## Normalization Formulas

Use simple, explainable formulas. Record the threshold used.

| Metric type | Formula |
| --- | --- |
| Boolean hard gate | `PASS=1`, `FAIL=0`; failure blocks the final score |
| Higher is better | `min(1, raw / target)` |
| Lower is better | `1 - min(1, raw / limit)` |
| Target band | `1 - min(1, abs(raw - target) / tolerance)` |
| Percent | clamp raw percent to `0..1` |
| Latency | `1 - min(1, p95Ms / budgetMs)` |
| Count regression | compare before/after; improvement positive only when direction is explicit |
| Stale or indirect evidence | keep normalized value, reduce confidence |

Default weights:

| Area | Weight |
| --- | --- |
| SourceSet and build truth | 0.20 |
| Runtime wiring and data contract | 0.20 |
| Prompt/search/provider safety | 0.15 |
| Failure handling and observability | 0.15 |
| Quantitative metric integrity | 0.15 |
| Scope and maintainability | 0.15 |

Hard gates override weighted score:

- active sourceSet not proven,
- LangChain4j version impurity,
- secret leak risk,
- PromptBuilder bypass in active final prompt path,
- duplicate FQCN in active roots,
- test/build success fabricated or stale,
- metric has no unit, direction, owner, or verifier.

## Combination-Breakage Checks

Check these pairs first for the attached Dynamic RAG design family:

| Pair | Breakage question |
| --- | --- |
| Overdrive vs ExtremeZ vs HYPERNOVA | Can the same sparse/contradictory/urgent trigger enable multiple high-risk modes at once? |
| MoE routing vs Plan DSL | Which one is authoritative when both change retrieval order, K allocation, or model route? |
| Matryoshka slicing vs vector DB | Are dimensions, normalization, and metadata type compatible end to end? |
| CFVM failure learning vs vector memory | Are raw failures redacted and quarantined before vector ingestion? |
| DPP or reranker scores vs RRF/KG/web scores | Are scores calibrated before fusion, or are ranks and probabilities mixed? |
| Massive parallel expansion vs TimeBudget/CancelShield | Can fan-out exhaust the global budget or poison later stages through interrupt propagation? |
| RuleBreak/Brave/FullScale modes vs safety gates | Is policy relaxation explicit, bounded, user-visible, and traceable? |
| UAW/autolearn vs PromptBuilder | Does training or prompt expansion bypass the final prompt boundary? |
| ONNX reranking vs boot safety | Does missing or placeholder model data disable cleanly instead of crashing boot? |

## Output Schema

```markdown
## 정량 정규화 요약
- attachment treated as: claim_map
- active source evidence: <confirmed|partial|missing>
- final normalized score: <0..100 or blocked>
- hard gates: <pass/fail list>

## Claim Ledger
| Claim | Live owner | Evidence | Metric | Unit | Direction | Raw | Normalized | Confidence | Verdict |
|---|---|---|---|---|---|---:|---:|---:|---|

## Broken Combination Matrix
| Pair | Failure mode | Evidence | Severity | Normalized risk | Next proof |
|---|---|---|---|---:|---|

## Patch/Handoff Decision
- decision: verify_only | write_antigravity_prompt | patch_directive | safe_patch_now | blocked_evidence_needed
- next single action:
- evidence_needed:
```

## Verification Commands

Use only available commands.

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
Get-Location
$indexOp = (Test-Path ".git\MERGE_HEAD") -or (Test-Path ".git\CHERRY_PICK_HEAD") -or (Test-Path ".git\rebase-merge") -or (Test-Path ".git\rebase-apply")
if ($indexOp) { Write-Error "[AWX][desktop] index-operation-active"; exit 1 }
rg --files -g "build.gradle*" -g "settings.gradle*" -g "gradlew*" -g "AGENTS.md" -g "agent-prompts/**" -g ".agents/skills/**/SKILL.md"
.\gradlew.bat sourceScoreReport --no-daemon --project-cache-dir "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
```

If Gradle cannot run, classify the blocker and continue with read-only file evidence only.
If `sourceScoreReport` fails, do not reuse `verification/source-score-report.txt` as current proof unless its timestamp and generating command are independently verified. Treat it as stale supporting evidence and continue with the ledger.
