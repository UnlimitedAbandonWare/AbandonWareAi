# Locale-Stable Smart Query Subject Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Prevent a subject already present in an LLM-selected search query from being prepended again when the JVM default locale is Turkish.

**Architecture:** Exercise the active optional `KeywordSelectionService` branch with a supported finance-domain query and resolved subject. Change only the subject-presence comparison to use `Locale.ROOT`; retain selected terms, domain caps, hygiene filtering, traces, and fallbacks.

**Tech Stack:** Java 17, JUnit 5, Mockito, Spring `ReflectionTestUtils`, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `L10N-SMARTQUERY-001`, and root [AGENTS.md](../../../AGENTS.md).

## Constraints

- Modify the clean tracked `SmartQueryPlanner` and its clean tracked focused test only.
- Do not modify the untracked `QueryHygieneFilter.java`; record its separately observed locale debt as source-owner `HOLD`.
- Restore the JVM locale in `finally`; avoid provider/network calls by mocking keyword selection.
- Require stable three-way `APPLY`, an owned lease, and unchanged preimages before production edit.

### Task 1: Prove duplicate subject output

- [x] Under `tr-TR`, return selected terms `IBM earnings`, resolved subject `ibm`, and `FINANCE` domain.
- [x] Require the output to remain exactly `IBM earnings`; verify the current branch returns a second `ibm IBM earnings` query.

### Task 2: Repair and verify

- [x] Normalize both operands with `Locale.ROOT` at the subject-presence comparison.
- [x] Run exact GREEN, the complete focused class, source gates, diff checks, hashes, and ledger update; release only the matching lease.
