# OpenAI Local-Model Endpoint Guard Locale-Stability Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Ensure the startup guard always rejects a local-model identifier configured against the official OpenAI endpoint, regardless of the JVM default locale or URL casing.

**Architecture:** Exercise the existing `OpenAiChatModel.normaliseAndValidate()` boundary with an uppercase official endpoint under Turkish locale. Change only the two protocol/model normalization calls used by the existing guard to `Locale.ROOT`; preserve the downstream local-gateway policy and all provider configuration.

**Tech Stack:** Java 17, JUnit 5, Spring WebFlux test client, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `LOCALE-OAIGUARD-001`, and root [AGENTS.md](../../../AGENTS.md).

## Global Constraints

- Modify exactly `main/java/com/example/lms/llm/OpenAiChatModel.java` and `src/test/java/com/example/lms/llm/OpenAiChatModelRedactionContractTest.java`.
- Preserve all endpoint selection, provider, secret, outbound-call, prompt, and LangChain4j contracts.
- Write and run RED before changing production code; restore the prior JVM locale in `finally`.
- Source edit requires stable three-way `APPLY`, an owned lease, and unchanged preimages `d3cced1b1b26b126e4a19fc6adcd807aa18c1f6dd416c9c018d8afeedf3c9a71` and `1f7ccc935cb4535a40f2dad2dec81a5d51c4f17337dae3104c272e97b4416f3f`.
- Do not commit, push, deploy, or modify unrelated dirty files.

---

### Task 1: Reproduce the locale-dependent startup-guard bypass

**Files:**

- Modify: `src/test/java/com/example/lms/llm/OpenAiChatModelRedactionContractTest.java`
- Test: `src/test/java/com/example/lms/llm/OpenAiChatModelRedactionContractTest.java`

**Interfaces:**

- Consumes: `OpenAiChatModel.normaliseAndValidate()` with `baseUrl=HTTPS://API.OPENAI.COM`, `defaultModel=QWEN2.5`, and JVM locale `tr-TR`.
- Produces: a behavior regression requiring `IllegalStateException` before any outbound request.

- [x] **Step 1: Add the failing behavior test**

Construct the model with a WebClient whose exchange function fails the test if called. Set the existing configuration fields with `ReflectionTestUtils`, change the default locale to Turkish in a `try/finally`, and assert that `normaliseAndValidate` throws.

Mutation check: reverting the two guard normalizations to zero-argument `toLowerCase()` makes the exception assertion fail under `tr-TR`.

- [x] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.llm.OpenAiChatModelRedactionContractTest.localModelOfficialEndpointGuardIsIndependentOfDefaultLocale" --no-daemon --console=plain --project-cache-dir C:\Users\nninn\.awx-gradle-project-cache\desktop-random-probe-100
```

Expected: one assertion failure because no exception is thrown. Compilation/setup/cache failures do not count.

### Task 2: Normalize protocol identifiers with `Locale.ROOT`

**Files:**

- Modify: `main/java/com/example/lms/llm/OpenAiChatModel.java`
- Test: `src/test/java/com/example/lms/llm/OpenAiChatModelRedactionContractTest.java`

**Interfaces:**

- Consumes: endpoint URLs and model IDs whose matching tokens are ASCII protocol identifiers.
- Produces: locale-independent matching while retaining the existing local-model/official-endpoint rejection message and downstream provider guard.

- [x] **Step 1: Apply the minimal production fix**

Import `java.util.Locale` and replace only the guard's two zero-argument lowercase calls with `toLowerCase(Locale.ROOT)`.

- [x] **Step 2: Run GREEN and focused gateway tests**

Run the exact RED command, then the complete `OpenAiChatModelRedactionContractTest` and the isolated `gatewaySecurityTest --tests "ai.abandonware.nova.orch.aop.LlmRouterGatewaySecurityTest"` task.

- [x] **Step 3: Run source gates and inspect the exact diff**

Run LangChain4j purity, sourceSet hygiene, and `compileJava -x test` with the isolated project cache. Require exit `0`, zero diff-check errors, two changed paths, postimage hashes, and release of only the owned batch lease.
