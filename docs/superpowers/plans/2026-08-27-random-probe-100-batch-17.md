# Persistent Chat-Memory Role Boundary Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Keep navigation-path metadata out of conversational LangChain4j memory replay.

**Architecture:** Filter only the existing dedicated `PATH_ROLE` records before the current user/assistant mapping. Preserve persistence schema, path-history retrieval, add/clear behavior, session ownership, message order, content, prompt assembly, and LangChain4j `1.0.1`.

**Tech Stack:** Java 17, LangChain4j 1.0.1, Spring Data repository mocks, JUnit 5, Mockito, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `RP11-01`, and root [AGENTS.md](../../../AGENTS.md).

## Constraints

- Modify one clean tracked memory adapter and add one collision-free focused test.
- Do not change database rows, session memory, schemas, prompt construction, or live runtime state.
- Keep unknown legacy conversational roles mapped exactly as before; exclude only the explicit metadata role.
- Require focused RED, stable three-way `APPLY`, owned lease, and unchanged source preimage before edit.

### Task 1: Prove metadata contamination

- [x] Replay a mixed user/path/assistant repository history and require only two conversational messages.
- [x] Preserve message order, types, and content.

### Task 2: Repair and verify

- [x] Filter exactly `PATH_ROLE` before conversational role mapping.
- [x] Run exact GREEN, memory/repository neighbors, source gates, diff checks, hashes, ledger update, and release the matching lease.

## Proof

- EvidenceSnapshot: `0bd74be5e69974517acfae46f44663974f2a994e0865fac1ecf2c4227dd3d2af`, 20 rows, 1,624 characters.
- Three-way preflight: stable `APPLY`, score `98.35`; metadata-exclusion, conversational-order, and legacy-role/path-history scenario IDs and decisive evidence were identical in A-B and B-A packet order.
- RED: the mixed-role focused test compiled and failed because replay returned three messages instead of two; the dedicated path row was mapped to an `AiMessage`.
- GREEN: both focused contracts passed after a one-line exact-role filter, including preserved `pathHistory()` and unknown legacy-role mapping.
- Focused boundary: 3 suites / 8 tests / 0 failures/errors/skips across persistent memory and chat-history conversation/redaction contracts.
- Source gates: LangChain4j purity, sourceSet hygiene, and compile exited `0`; LangChain4j stayed at `1.0.1` and the known inactive app source remained informational.
- Exact scope: one tracked source insertion plus one 46-line focused test; no whitespace errors and no live database/session-memory mutation.
- Postimage SHA-256: `PersistentChatMemory.java=54e79a05f6e1a73fe0566cf23abf6e47de6433b6dad8c6c06a038995d9b22b72`; `PersistentChatMemoryTest.java=636965d32d2d9329d2fa8a4a2d4db38baa595e979751b116ae7cf17a7016929d`.
