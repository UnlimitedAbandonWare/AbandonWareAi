# Locale-Stable Generic-Document Domain Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Preserve the education-domain exemption under every JVM default locale.

**Architecture:** Add direct no-Spring behavior tests for the clean tracked generic-document owner. Apply `Locale.ROOT` only to its two domain-identifier normalizations; retain patterns, penalties, ranking weights, and callers.

**Tech Stack:** Java 17, JUnit 5, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `L10N-GENERICDOC-001`, and root [AGENTS.md](../../../AGENTS.md).

## Constraints

- Modify exactly one clean tracked source and create one visible focused test.
- This is identifier normalization only; do not change ranking weights, generic patterns, query algorithms, providers, configuration, or public APIs.
- Require two behavior REDs, stable three-way `APPLY`, owned lease, and unchanged preimages before production edits.
- Do not modify either EvidenceAwareGuard owner in this batch; the active owner is already dirty and remains `HOLD`.
- `QueryHygieneUtil.looksLikeCorp` produced a direct RED but has no production caller or bean reachability; remove its task-owned test and retain the row as `CANDIDATE_ZOMBIE`.

### Task 1: Prove classification drift

- [x] Require both generic-snippet and generic-text education exemptions under `tr-TR`.
- [x] Disconfirm `QueryHygieneUtil` runtime reachability and remove its task-owned RED test.
- [x] Run both selected generic-document tests and require two behavior failures.

### Task 2: Repair and verify

- [x] Normalize only the two domain identifier operands with `Locale.ROOT`.
- [x] Run exact GREEN, full focused classes, source gates, diff checks, hashes, ledger update, and release the matching lease.
