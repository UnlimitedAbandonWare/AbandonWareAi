# Locale-Stable Answer, Disambiguation, and Reranker Policy Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Preserve three supported policy identifiers—answer mode, LLM confidence, and ONNX reranker backend—under any JVM default locale.

**Architecture:** Add one behavior regression per public seam under `tr-TR`, restoring the prior locale in `finally`. Replace only the three identifier-normalization calls with `Locale.ROOT`; retain all defaults, fallbacks, toggles, and bean selection.

**Tech Stack:** Java 17, JUnit 5, LangChain4j `Content` test stubs, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issues `L10N-ANSWERMODE-001`, `L10N-DISAMBIGCONF-001`, and `L10N-RERANKER-001`, and root [AGENTS.md](../../../AGENTS.md).

## Global Constraints

- Modify exactly three clean tracked sources, the clean tracked `RerankerSelectorTest`, and create two visible focused tests.
- Preserve unknown/blank AnswerMode fallback, score-based confidence, reranker bean names/toggles/fallbacks, prompt/provider/secret contracts, and LangChain4j `1.0.1`.
- Use supported values `creative`, `HIGH`, and `ONNX-RUNTIME`; run all three RED probes before production edits.
- Require stable three-way `APPLY`, owned lease, and unchanged preimages recorded in the frozen snapshot.
- Do not modify the already-dirty `QueryTransformerRedactionContractTest`; the query-failure candidate remains a separate shared-resilience seam.
- Do not call providers, commit, push, deploy, or modify unrelated dirty files.

---

### Task 1: Prove three supported-policy failures

- [x] Parse `creative` under `tr-TR` and require `AnswerMode.CREATIVE` rather than `ALL_ROUNDER`.
- [x] Set `DisambiguationResult.confidence=HIGH` under `tr-TR` and require `isConfident()` to return true.
- [x] Configure `RerankerSelector` with `ONNX-RUNTIME`, both ONNX toggles enabled, and both reranker beans; require the ONNX bean rather than embedding fallback.
- [x] Run the three selected tests and require three behavior failures; setup/compilation failures are invalid RED evidence.

### Task 2: Use locale-neutral policy normalization

- [x] Add `Locale.ROOT` to the three source normalization calls.
- [x] Run the exact tests GREEN, then the three complete focused classes.
- [x] Run purity, sourceSet hygiene, and compile; inspect the six-path diff/visibility, record hashes and test counts, update the ledger, and release only the matching lease.
