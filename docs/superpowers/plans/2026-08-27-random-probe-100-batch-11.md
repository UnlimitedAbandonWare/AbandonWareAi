# Locale-Stable GraphDB Failure Classification Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Preserve the existing `cancelled` GraphDB failure class for interrupt-shaped status/class identifiers under every JVM locale.

**Architecture:** Extend the focused no-Spring reflection test at the two active GraphDB classification boundaries. Use `Locale.ROOT` only for failure-class comparison text; retain synthetic code redaction, hash behavior, manifests, and exception handling.

**Tech Stack:** Java 17, JUnit 5 reflection, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issues `L10N-GRAPHFAILURE-001` and `L10N-GRAPHMANUALFAILURE-001`, and root [AGENTS.md](../../../AGENTS.md).

## Constraints

- Modify only the two task-owned GraphDB sources and the task-owned focused test.
- Keep hash-token normalization unchanged; hexadecimal input is locale-stable.
- Require three behavior REDs across the two active boundaries, stable three-way `APPLY`, owned lease, and unchanged Batch 10 postimages before source edit.

### Task 1: Prove failure-class drift

- [x] Require `GraphDbClient.safeFailureClass("INTERRUPTED")` to return `cancelled` under `tr-TR`.
- [x] Require manual-learning `failureClass(InterruptedGraphException)` to return `cancelled` under `tr-TR`.
- [x] Require a neutral exception with message `INTERRUPTED` to exercise the separate manual message operand.
- [x] Run all three new tests and require three behavior failures.

### Task 2: Repair and verify

- [x] Normalize only classification operands with `Locale.ROOT`.
- [x] Run exact GREEN, full focused/GraphDB neighbors, source gates, diff checks, hashes, ledger update, and release the matching lease.
