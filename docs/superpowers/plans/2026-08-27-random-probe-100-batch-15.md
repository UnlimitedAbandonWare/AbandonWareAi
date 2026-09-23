# Soak Top-K Boundary Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Bound caller-controlled `k` before the active internal soak controller invokes retrieval work.

**Architecture:** Normalize only the two controller entry points to the repository's existing public-request default top-K ceiling of 100 and a lower bound of 1. Preserve endpoint paths, response schemas, topics, service interfaces, security ownership, and downstream orchestration.

**Tech Stack:** Java 17, Spring MVC, JUnit 5, Mockito, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `RC7-03`, and root [AGENTS.md](../../../AGENTS.md).

## Constraints

- Modify one clean tracked controller and its clean tracked focused test.
- Do not touch the pre-intake-dirty security configuration; existing admin authorization stays outside this resource-bound repair.
- Do not call retrieval providers, databases, or a live endpoint.
- Require focused RED, stable three-way `APPLY`, owned lease, and unchanged preimages before source edit.

### Task 1: Prove unbounded forwarding

- [x] Pass `Integer.MAX_VALUE` through `/run` and require forwarding as 100.
- [x] Pass `Integer.MAX_VALUE` through `/quick` and require forwarding as 100.
- [x] Pass nonpositive values and require both entry points to forward 1.

### Task 2: Repair and verify

- [x] Add one shared controller-local top-K normalizer with bounds 1..100.
- [x] Run exact GREEN, focused soak/controller neighbors, source gates, diff checks, hashes, ledger update, and release the matching lease.

## Proof

- EvidenceSnapshot: `7e6134690268da24729c7592ac82fa3ca121b5e0c8f91e170d24c2353638ee5d`, 20 rows, 1,730 characters.
- Three-way preflight: stable `APPLY`, score `96.20`; oversized `/run`, oversized `/quick`, and nonpositive-bound scenario IDs and decisive evidence were identical in A-B and B-A packet order. The pre-intake-dirty security owner remained excluded.
- RED: the five-test class compiled; both existing redaction contracts passed and all three new forwarding contracts failed because the controller passed raw `k` values.
- GREEN: the full controller class passed 5 tests after both entry points used one 1..100 normalizer.
- Focused boundary: 4 suites / 33 tests / 0 failures/errors/skips across the controller, service, admin-token guard, and exception-redaction contracts.
- Source gates: LangChain4j purity, sourceSet hygiene, and compile exited `0`; known inactive `app/src/main/java` remained informational.
- Exact scope: 2 tracked files, 44 insertions / 2 deletions, no whitespace errors; no provider, database, or live endpoint was called.
- Postimage SHA-256: `SoakApiController.java=31fa8c3bac5e4c39224ddb393eda45a9cc9665834627134591c8089ad1de776a`; `SoakApiControllerRedactionTest.java=06833b5ceffc73dfdb22980ab226a6d1747f5baa08f38e4f99f9c9974671db6f`.
