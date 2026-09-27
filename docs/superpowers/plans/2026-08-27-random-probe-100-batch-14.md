# Settings Response Secret-Masking Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Prevent database credentials, AWS access-key settings, and PEM private-key material from being returned raw by the active settings-response aspect.

**Architecture:** Extend the direct aspect test with synthetic non-live secret shapes under both sensitive and innocuous keys. Expand only key/value recognition; retain response status/headers, safe settings, mask formatting, trace counts, write policy, and controller behavior.

**Tech Stack:** Java 17, JUnit 5, Mockito, Spring `MockEnvironment`, AspectJ API, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `RC6-01`, and root [AGENTS.md](../../../AGENTS.md).

## Constraints

- Modify one clean tracked aspect and its clean tracked focused test.
- Use synthetic non-live values only; do not read settings, databases, credentials, environment values, or controller responses.
- Preserve masking default-on behavior and all safe-value passthrough.
- The dirty security configuration blocks fresh endpoint-authorization certification, not this independent response-redaction repair.
- Require focused RED, stable three-way `APPLY`, owned lease, and unchanged preimages before source edit.

### Task 1: Prove response leaks

- [x] Return a JDBC URL with synthetic userinfo under an innocuous key and require masking.
- [x] Return an AWS access-key-shaped value under `aws_access_key_id` and require masking.
- [x] Return PEM private-key-shaped material under an innocuous key and require masking.
- [x] Require an unrelated safe setting to remain unchanged.

### Task 2: Repair and verify

- [x] Add only bounded key/value shape recognition for the three proven cases.
- [x] Run exact GREEN, full focused class, source gates, diff checks, hashes, ledger update, and release the matching lease.

## Proof

- EvidenceSnapshot: `b0477b49700a464058c65253f302b4f3cf83252b608cf15996199f3ebc0504eb`, 20 rows, 1,861 characters.
- Three-way preflight: stable `APPLY`, score `96.60`; JDBC-userinfo, AWS-access-key, and PEM-private-key scenario IDs and decisive evidence were identical in A-B and B-A packet order. Endpoint authorization stayed outside this repair because its owner was dirty before intake.
- RED: the original five-test class compiled, its two existing contracts passed, and the three new leak contracts failed because each raw synthetic value remained in the response.
- GREEN: all six aspect contracts passed, including the three redaction cases, safe-value preservation, and a negative URI-query boundary case.
- Focused boundary: 6 suites / 30 tests / 0 failures/errors/skips across the aspect, settings controllers/merger, and service redaction contracts.
- Source gates: LangChain4j purity, sourceSet hygiene, and compile exited `0`; the known inactive `app/src/main/java` remained informational.
- Exact scope: 2 tracked files, 80 insertions / 1 deletion, no whitespace errors; one count-only secret-pattern hit is the deliberately synthetic PEM fixture, not a live value.
- Postimage SHA-256: `SettingsControllerSecretMaskAspect.java=09f300d8f4df06cb72a3e3d1bcb17bf582da9b36a21e1f75b8a8c37c14acd83e`; `SettingsControllerSecretMaskAspectTest.java=0a090765a633e4978d4cd4dd3eda85cf48e32c84fd788d94c06e87fa961ee336`.
