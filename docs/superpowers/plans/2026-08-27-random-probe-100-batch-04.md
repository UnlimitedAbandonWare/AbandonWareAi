# Locale-Stable Security and Policy Normalization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Keep scanner-path blocking, official OpenAI URL recognition, and configured guard-profile parsing independent of the host JVM locale.

**Architecture:** Add one real behavior test per active boundary under a temporary Turkish default locale, restoring global state in `finally`. Replace only protocol/policy zero-argument case normalization with `Locale.ROOT`; do not change blocklists, allowed endpoints, profile fallback behavior, or route wiring.

**Tech Stack:** Java 17, JUnit 5, Spring MockHttpServletRequest/Response, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issues `L10N-SPATH-001`, `L10N-MGS-001`, and `L10N-GUARDPROFILE-001`, and root [AGENTS.md](../../../AGENTS.md).

## Global Constraints

- Modify exactly:
  - `main/java/com/example/lms/web/SuspiciousPathBlockFilter.java`
  - `src/test/java/com/example/lms/web/SuspiciousPathBlockFilterTest.java`
  - `main/java/ai/abandonware/nova/orch/llm/ModelGuardSupport.java`
  - create `src/test/java/ai/abandonware/nova/orch/llm/ModelGuardSupportLocaleTest.java`
  - `main/java/com/example/lms/guard/GuardProfileProps.java`
  - create `src/test/java/com/example/lms/guard/GuardProfileLocaleTest.java`
- Preserve security blocklist contents, provider routing, profile fallback, traces, secrets, prompt boundaries, and LangChain4j versions.
- Run all three RED probes before any production edit; restore the prior locale even when assertions fail.
- Require stable three-way `APPLY`, owned lease, and unchanged tracked preimages recorded in the frozen snapshot.
- Do not commit, push, deploy, or modify unrelated dirty files.

---

### Task 1: Add three deterministic Turkish-locale regressions

- [x] Add `blocksMixedCaseGitProbeUnderTurkishDefaultLocale` to `SuspiciousPathBlockFilterTest`; send `/.GIT/config`, require status 404, and require the downstream chain was not reached.
- [x] Create `ModelGuardSupportLocaleTest`; require `looksLikeOpenAiBaseUrl("HTTPS://API.OPENAI.COM/V1")` to return true.
- [x] Create `GuardProfileLocaleTest`; set `profile_free` and require `PROFILE_FREE`.
- [x] Run each selected test separately and require a behavior assertion failure for each. Compilation/setup/cache failures are not RED evidence.

### Task 2: Make the three normalization seams locale-independent

- [x] In `SuspiciousPathBlockFilter`, import `Locale` and use `Locale.ROOT` for URI and query normalization.
- [x] In `ModelGuardSupport`, use its existing `Locale` import for OpenAI base-URL normalization.
- [x] In `GuardProfileProps`, import `Locale` and use `Locale.ROOT` for enum-name normalization.
- [x] Run the exact three RED commands again, then the full three focused test classes.
- [x] Run `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, and `compileJava -x test` with the isolated Desktop cache.
- [x] Inspect the six-path diff, require no whitespace errors, record postimage hashes, update the ledger, and release only the owned batch lease.
