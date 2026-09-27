# Locale-Stable Admin Status, Diagnostic Header, and Chat Role Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Prevent Turkish/default-locale case mappings from activating the wrong vector-memory state, dropping BLUE request IDs, or persisting an invalid chat role.

**Architecture:** Test each public boundary with a supported ASCII identifier containing `I` under `tr-TR`, always restoring the prior JVM locale. Change only the corresponding case-normalization call to `Locale.ROOT`; preserve admin fallback behavior for genuinely invalid values, header allowlists/value limits, JPA mappings, and message content.

**Tech Stack:** Java 17, JUnit 5, Mockito, Spring HttpHeaders/ResponseEntity, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issues `L10N-VECTOR-001`, `L10N-BLUEHEADER-001`, and `L10N-CHATROLE-001`, and root [AGENTS.md](../../../AGENTS.md).

## Global Constraints

- Modify exactly three clean tracked sources and create three visible focused tests:
  - `main/java/com/example/lms/api/VectorAdminController.java`
  - `src/test/java/com/example/lms/api/VectorAdminControllerLocaleTest.java`
  - `main/java/com/example/lms/scheduler/BlueHeaderWhitelist.java`
  - `src/test/java/com/example/lms/scheduler/BlueHeaderWhitelistLocaleTest.java`
  - `main/java/com/example/lms/domain/ChatMessage.java`
  - `src/test/java/com/example/lms/domain/ChatMessageLocaleTest.java`
- Preserve security authorization, repository writes, valid/invalid status fallback semantics, header names/value limits, entity schema, prompt/provider/secret contracts, and LangChain4j versions.
- Run all three RED probes before production edits; source edit requires stable three-way `APPLY`, owned lease, and unchanged source preimages recorded in the frozen snapshot.
- Do not call a database or provider. Do not commit, push, deploy, or modify unrelated dirty files.

---

### Task 1: Prove three supported-value failures

- [x] Directly call `VectorAdminController.updateQuarantine` with mocked dependencies and supported status `pending` under `tr-TR`; require persisted/returned `PENDING`, not fallback `ACTIVE`.
- [x] Extract uppercase `X-REQUEST-ID` under `tr-TR`; require key `x-request-id` and the original safe value.
- [x] Construct a `ChatMessage` with role `ASSISTANT` under `tr-TR`; require stored role `assistant`.
- [x] Run the three selected tests and require three behavior failures. Compilation/setup/cache failures do not count.

### Task 2: Normalize protocol and enum identifiers with `Locale.ROOT`

- [x] Use `Locale.ROOT` for the vector status uppercase operation, BLUE header lowercase operation, and ChatMessage role lowercase operation.
- [x] Run the same three tests GREEN and then the three complete focused classes.
- [x] Run LangChain4j purity, sourceSet hygiene, and compile with the isolated Desktop cache.
- [x] Inspect the six-path diff and visibility, record hashes and test counts, update the ledger, and release only the matching lease.
