# Locale-Stable GraphDB Sensitive-Code Redaction Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Keep authorization-shaped GraphDB diagnostic codes redacted under every JVM default locale.

**Architecture:** Invoke each active service's private static `safeCode` boundary without Spring, GraphDB, network, or credentials. Replace only the two sensitive-code lowercasing calls with `Locale.ROOT`; retain allowlists, returned codes, manifests, and service behavior.

**Tech Stack:** Java 17, JUnit 5 reflection, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issues `L10N-GRAPHCLIENT-001` and `L10N-GRAPHMANUAL-001`, and root [AGENTS.md](../../../AGENTS.md).

## Constraints

- Modify exactly the two clean tracked GraphDB sources and create one visible focused test.
- Use only the synthetic non-secret label `AUTHORIZATION`; never inspect or emit credentials.
- Require two independent RED assertions, stable three-way `APPLY`, owned lease, and unchanged source preimages.
- Treat a reappearing Git index lock as lane-local `HOLD`; never remove it.

### Task 1: Prove both redaction bypasses

- [x] Under `tr-TR`, invoke `GraphDbClient.safeCode("AUTHORIZATION")` and require `redacted`.
- [x] Under `tr-TR`, invoke `GraphDbManualLearningService.safeCode("AUTHORIZATION")` and require `redacted`.
- [x] Run both tests together and require two behavior assertion failures.

### Task 2: Repair and verify

- [x] Use `Locale.ROOT` at only the two sensitive-code normalization calls.
- [x] Run exact GREEN, closest GraphDB tests, source gates, diff checks, hashes, ledger update, and release only the matching lease.
