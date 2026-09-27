# Knowledge-Decay Synergy Snapshot Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Eliminate repeated full-table synergy reads from one active knowledge-decay run without changing decay math or scheduling.

**Architecture:** Load the synergy snapshot once per scheduled invocation through a fail-soft helper, then reuse that immutable run-local list for each knowledge item. Preserve confidence factors, matching, minimum score, save policy, trace stage, logs, property gate, and schedule.

**Tech Stack:** Java 17, Spring Data repositories, JUnit 5, Mockito, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `RP9-04`, and root [AGENTS.md](../../../AGENTS.md).

## Constraints

- Modify one clean tracked service and add one collision-free focused test.
- Use repository mocks only; do not call a database or schedule a live job.
- Preserve the existing fail-soft trace key and all confidence-decay arithmetic.
- Require focused RED, stable three-way `APPLY`, owned lease, and unchanged source preimage before edit.

### Task 1: Prove repeated reads

- [x] Supply three qualifying knowledge rows and require one synergy-table read for the run.
- [x] Require all three confidence updates to remain persisted.

### Task 2: Repair and verify

- [x] Move only the fail-soft synergy snapshot load outside the item loop.
- [x] Run exact GREEN, focused agent/scheduler neighbors, source gates, diff checks, hashes, ledger update, and release the matching lease.

## Proof

- EvidenceSnapshot: `9baafec8e8997f7bcdd0c907242948ae92d5d91cecedb3f18548b921ad76318d`, 20 rows, 1,677 characters.
- Three-way preflight: stable `APPLY`, score `97.15`; one-read, persistence-preservation, and fail-soft lookup scenario IDs and decisive evidence were identical in A-B and B-A packet order.
- RED: the focused test compiled and failed with Mockito `TooManyActualInvocations`; three qualifying rows caused three full synergy-table reads instead of one.
- GREEN: both focused contracts passed after using one run-local snapshot, including repository-failure fail-soft behavior and continued confidence persistence.
- Focused boundary: 3 suites / 17 tests / 0 failures/errors/skips across knowledge decay, agent redaction, and scheduled-job governance.
- Source gates: LangChain4j purity, sourceSet hygiene, and compile exited `0`; known inactive `app/src/main/java` remained informational.
- Exact scope: one tracked source with 11 insertions / 1 deletion plus one 49-line focused test; no whitespace errors and no database/scheduler call.
- Postimage SHA-256: `KnowledgeDecayService.java=dd3a1ef12cca51114ec23a9decc51c3d9c479c7cc4638b2a48cbfc44f7cebae2`; `KnowledgeDecayServiceTest.java=b319bd098574efd8e7d05b528a19743a4ec31a97d36dd71073a6c259b41db107`.
