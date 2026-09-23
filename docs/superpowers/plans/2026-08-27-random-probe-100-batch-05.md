# Redis Cooldown Shared-Connection Containment Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Prevent concurrent request paths from using the singleton service's non-thread-safe Jedis connection at the same time, and ensure Spring closes that connection during bean destruction.

**Architecture:** Preserve the repository's deliberately lightweight, non-pooled single-Jedis design and exact atomic `SET NX EX` command. Serialize the two operations that touch the shared client (`setNxEx` and shutdown `close`) at the service boundary and add `@PreDestroy`. A deterministic Mockito/latch contract proves the shared client is never entered concurrently without requiring Redis, Docker, network access, or a new dependency.

**Tech Stack:** Java 17, Jedis 5.0.2 (fresh `dependencyInsight`), JUnit 5, Mockito, Spring ReflectionTestUtils, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `RCS-01`, and root [AGENTS.md](../../../AGENTS.md).

## Global Constraints

- Modify exactly `main/java/com/example/lms/service/redis/RedisCooldownService.java` and create `src/test/java/com/example/lms/service/redis/RedisCooldownServiceTest.java`.
- Preserve Redis host/port properties, key/value/TTL handling, atomic `SET NX EX`, return values, redacted close diagnostics, and caller fail-soft behavior.
- Do not add or upgrade a dependency, introduce a pool/configuration framework, call a live Redis endpoint, or change unrelated backoff code.
- Run both RED contracts before production edits; source edit requires stable three-way `APPLY`, an owned lease, and unchanged source preimage `08efb2564b1c86df79058fde51c06c4399f9303f38c01bb492bc0ccfac706536`.
- Do not commit, push, deploy, or modify unrelated dirty files.

---

### Task 1: Prove concurrent entry and missing lifecycle ownership

- [x] Create a Mockito-backed concurrency test. Inject one mock Jedis into the current service, release two callers together, hold the first command in the mock, and require that no second caller enters before release. Current code must fail because both calls enter the same client concurrently.
- [x] Add a reflection contract requiring `RedisCooldownService.close()` to carry `jakarta.annotation.PreDestroy`. Current code must fail because the annotation is absent.
- [x] Run the two selected tests and require exactly two behavior failures; compilation/setup/cache failures are invalid RED evidence.

### Task 2: Serialize shared-client access and register shutdown cleanup

- [x] Make `setNxEx` synchronized without changing its command construction or return semantics.
- [x] Add `@PreDestroy` and synchronize `close` so shutdown cannot close the socket during an in-flight cooldown command.
- [x] Run the exact RED command again, then the full focused test class.
- [x] Run the closest caller tests found for Naver cooldown and hybrid rerank cooldown if their exact methods are selectable; otherwise run the source gates and record the missing focused caller seam without inventing coverage.
- [x] Run LangChain4j purity, sourceSet hygiene, and compile; inspect the two-path diff, hashes, and whitespace; update the ledger and release only the matching lease.
