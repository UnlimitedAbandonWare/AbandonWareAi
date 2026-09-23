# Python Diagnostic Meta Skill Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. Parent owns edits and integration; bounded agents collect evidence only under repository routing rules.

**Goal:** Deliver the approved observation, reproduction, classification, minimal repair and revalidation meta skill with executable Python metrics.

**Architecture:** Reuse registry identity, offline ai_debug_assist classification and the existing DebugCasePacket. Keep discovery/CLI in tools/skill_diagnostics.py and trace validation/metrics in tools/skill_diagnostics_trace.py; this one-file split keeps separate contracts reviewable without changing the approved behavior.

**Tech Stack:** Python 3.11 standard library, unittest, existing PowerShell family validator.

**Spec:** ../specs/2026-09-14-python-diagnostic-meta-skill-design.md (user approved revised design in this task).

## Global Constraints

- Parent writes only new scoped helper/test/skill files and this task's docs/evidence; preserve other writers and Git index lock. No commit, push or runtime/application source change is included.
- Defaults: 5,000 files; 1 MiB/file; 64 MiB scan bytes; 60 seconds; 64 KiB/event; 32 MiB trace input.
- Discovery never imports discovered code. Existing adapters are explicitly selected. No external provider is required.
- Store opaque inputs, hashes, counters, safe location and reason codes. No raw log/prompt/response/exception message or environment values.
- Zero denominator is null; static findings are candidates; incomplete traces cannot certify improvement.
- Incident ownership remains demo1-evidence-debugging; normal skill/application behavior is preserved.

## Task 1: Trace and measurement contract

Files: create tools/skill_diagnostics_trace.py; create scripts/test_skill_diagnostics.py.

Interfaces: TraceRecorder(root, path, context), recorder.decision(action, evidence_refs=(), evidence_required=True), recorder.tool(tool_id, call_id=None, attempt=1), recorder.set_result(status); aggregate(root, paths, expected_skills=()); compare(before, after, root=root).

- [x] Write explicit success/failure/retry, missing-data, redaction and provenance tests. A representative oracle is `assert metrics['toolSuccessRate'] == {'numerator': 2, 'denominator': 3, 'value': 2/3}` for one failed attempt and two successful attempts.
- [x] Run `python -B -X utf8 -m unittest discover -s scripts -p test_skill_diagnostics.py -q`; observe missing-feature assertions.
- [x] Implement exclusive bounded JSONL recording, result/exception transparency, strict schema, per-run correlation, counts and quantiles.
- [x] Match case/input/cohort/environment/oracle/policy before comparing; reject changed normal-path outcomes, new failures and incomplete observations.
- [x] Run the same focused command and resolve real failures without weakening oracles.

## Task 2: Discovery and adapter integration

Files: create tools/skill_diagnostics.py; extend scripts/test_skill_diagnostics.py.

Interfaces: scan(root, scopes=None, cache=None, limits=None); run_adapter(root, output, adapter, log=None, synthetic_input=False); CLI scan, run, report, compare, triage, reproduce.

- [x] Test actual temporary files: quiet except, preserved exception, invalid Python, UTF-8 skill, excluded cache, content-change cache invalidation, path escapes and byte/file caps.
- [x] Reuse `scripts.awx_skill_registry.scan_skills(shared, [])` only after bounded path checks; preserve partial/error status instead of inventing clean inventory.
- [x] Add explicitly selected `registry` and offline `log-diagnose` adapters. Reference existing family summary artifacts without changing their schema or treating their self-declared success as fresh proof.
- [x] Produce redacted inventory/findings/cache, correlated events and metrics; never overwrite an existing run output.
- [x] Execute real repo scan, unchanged cached scan and safe adapters; retain observation coverage and current hashes.

## Task 3: Reproduction and repair evidence

Files: tools/skill_diagnostics.py and scripts/test_skill_diagnostics.py; task-owned evidence directory.

- [x] Add a deterministic synthetic replay of an unsupported successful judgment and its evidence-bound correction. Reuse fixed case/oracle/policy IDs and preserve the normal case.
- [x] Save before/after events, metrics and comparison. Label synthetic results explicitly; require observed original-case failures and unchanged normal outcomes.
- [x] Investigate only a decision-changing real scan finding. If no real semantic RED exists, leave the target unchanged and record candidate/evidence_needed with one next check.
- [x] Preserve snapshot and preimage in repair eligibility output. It is evidence eligibility, never write authority; source repairs use the existing owner gate.

## Task 4: Skill delivery and verification

Files: .agents/skills/demo1-observed-debugging-meta/{SKILL.md,agents/openai.yaml,references/measurement-contract.md}; approved design status and completion evidence.

- [x] Document executable commands and schema limitations, DebugCasePacket/E-ID linkage, denominators, normal-path preservation and correction loop.
- [x] Run `python -B -X utf8 C:/Users/nninn/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/demo1-observed-debugging-meta`.
- [x] Run new helper tests and existing test_ai_debug_assist; run existing family summary with an explicit current self-test status, refreshing only when required.
- [x] Independently exercise the skill with incomplete evidence and a reproduced defect; keep observations distinct from hypotheses. Parent adjudicates and fixes supported gaps.
- [x] Audit every approved spec section against current files and command evidence; report actual improvements, unknowns and exact commands. Complete the goal only after all required deliverables are verified.

Completion evidence: `data/agent-handoff/skill-diagnostics/implementation-20260914-01a09ef2/summary.md` and `verification-complete.json`. Application source is unchanged. Global discovery drift was observed; unchanged-cache proof uses six fixed owned files. The final test harness correction changed only invocation cwd/module naming, not source or test expectations.
