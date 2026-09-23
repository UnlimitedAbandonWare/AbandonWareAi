# Locale-Stable Desktop Endpoint Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Keep redacted Desktop endpoint identity stable under non-English JVM locales.

**Architecture:** Directly verify the controller's private redaction helpers through reflection, while the existing public MockMvc contract continues to cover endpoint rendering and secret exclusion. Replace only the two host-rendering lowercase calls with `Locale.ROOT`.

**Tech Stack:** Java 17, JUnit 5, Spring MockMvc, reflection, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `L10N-ROUTER-001`, and root [AGENTS.md](../../../AGENTS.md).

**Scope correction:** `L10N-UPSTASH-001` was disconfirmed before source preflight. Spring's canonical source name is `systemEnvironment`; its ASCII `i` is already lowercase, so Turkish lowercasing does not change the comparison. No Upstash test or source edit is authorized in this batch.

## Global Constraints

- Modify exactly `main/java/com/example/lms/api/DesktopRouterStatusBridgeController.java` and `src/test/java/com/example/lms/api/DesktopRouterStatusBridgeControllerTest.java`.
- Preserve endpoint paths/ports, redaction, authorization, profile/property conditions, provider/secret/prompt contracts, and LangChain4j versions.
- Restore the prior JVM locale in `finally`; run RED before the production edit.
- Require stable three-way `APPLY`, owned lease, and unchanged two-file preimages from the frozen snapshot.
- Do not call providers, inspect environment secrets, commit, push, deploy, or modify unrelated dirty files.

---

### Task 1: Prove the locale-dependent endpoint rendering failure

- [x] Under `tr-TR`, invoke `endpointHostPort` and `redactedUrl` for uppercase `DESKTOP-GPU.INTERNAL`; require ASCII lowercase `desktop-gpu.internal` in both outputs. Current code must emit dotless `ı`.
- [x] Run the selected test and require two failing assertions from the one normalization root cause; setup/compilation failures are invalid RED evidence.

### Task 2: Use locale-neutral identifier normalization

- [x] Add `Locale.ROOT` to both Desktop host-rendering calls.
- [x] Run the exact test GREEN, then the complete focused controller test class.
- [x] Run purity, sourceSet hygiene, and compile; inspect the exact two-file diff and hashes, update the ledger, and release only the matching lease.
