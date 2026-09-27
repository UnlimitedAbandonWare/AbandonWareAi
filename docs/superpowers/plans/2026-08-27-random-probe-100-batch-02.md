# OpenAI Base-URL Locale Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Keep official OpenAI endpoint and token-parameter selection stable when the JVM default locale is Turkish or another locale with non-ASCII case mappings.

**Architecture:** Test the consumer-visible `tokenParamKey` result under a temporary Turkish default locale, restoring global state in `finally`. Change only the base-URL normalization seam to use `Locale.ROOT`; leave model-family and provider behavior otherwise unchanged.

**Tech Stack:** Java 17, JUnit 5, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `LOCALE-OAI-001`, and root [AGENTS.md](../../../AGENTS.md).

## Global Constraints

- Modify exactly `main/java/com/example/lms/llm/OpenAiTokenParamCompat.java` and create `src/test/java/com/example/lms/llm/OpenAiOfficialEndpointLocaleTest.java`. The class name intentionally avoids the repository's `/**/*token*.*` ignore rule.
- Preserve LangChain4j `1.0.1`, provider selection, payload keys, secrets, and prompt boundaries.
- Write and run RED before changing production code; restore the prior JVM locale even if the assertion fails.
- Source edit requires stable three-way `APPLY`, an owned lease, and unchanged source preimage.
- Do not commit, push, deploy, or modify unrelated dirty files.

---

### Task 1: Reproduce Turkish-locale endpoint misclassification

**Files:**

- Create: `src/test/java/com/example/lms/llm/OpenAiOfficialEndpointLocaleTest.java`
- Test: `src/test/java/com/example/lms/llm/OpenAiOfficialEndpointLocaleTest.java`

**Interfaces:**

- Consumes: `OpenAiTokenParamCompat.tokenParamKey(String modelName, String baseUrl)`.
- Produces: a real-behavior regression proving uppercase official endpoints still select `max_completion_tokens` for GPT-5.

- [x] **Step 1: Create the failing test**

Create:

```java
package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiOfficialEndpointLocaleTest {

    @Test
    void officialEndpointTokenParameterIsIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertEquals(
                    "max_completion_tokens",
                    OpenAiTokenParamCompat.tokenParamKey("gpt-5", "HTTPS://API.OPENAI.COM/V1"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
```

Mutation check: reverting `Locale.ROOT` to zero-argument `toLowerCase()` makes the literal token-key assertion fail under `tr-TR`.

- [x] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.llm.OpenAiOfficialEndpointLocaleTest.officialEndpointTokenParameterIsIndependentOfDefaultLocale" --no-daemon --console=plain --project-cache-dir C:\Users\nninn\.awx-gradle-project-cache\desktop-random-probe-100
```

Expected: one assertion failure, actual `max_tokens`, expected `max_completion_tokens`; compilation or locale-leak setup failures do not count.

### Task 2: Normalize base URLs with `Locale.ROOT`

**Files:**

- Modify: `main/java/com/example/lms/llm/OpenAiTokenParamCompat.java`
- Test: `src/test/java/com/example/lms/llm/OpenAiOfficialEndpointLocaleTest.java`

**Interfaces:**

- Consumes: provider base URLs whose host matching is ASCII protocol logic.
- Produces: locale-independent normalized base URLs without changing trailing-slash or `/v1` removal.

- [x] **Step 1: Apply the minimal implementation**

Add `import java.util.Locale;` and replace only:

```java
String s = raw.trim().toLowerCase();
```

with:

```java
String s = raw.trim().toLowerCase(Locale.ROOT);
```

- [x] **Step 2: Run GREEN and focused compatibility tests**

Run the exact RED command, then:

```powershell
.\gradlew.bat test --tests "com.example.lms.llm.OpenAiOfficialEndpointLocaleTest" --tests "ai.abandonware.nova.orch.aop.LlmRouterGatewaySecurityTest" --tests "com.example.lms.service.ChatWorkflowS7PromptBoundaryContractTest" --no-daemon --console=plain --project-cache-dir C:\Users\nninn\.awx-gradle-project-cache\desktop-random-probe-100
```

- [x] **Step 3: Run source gates and inspect the exact diff**

Run LangChain4j purity, sourceSet hygiene, and `compileJava -x test` with the isolated cache. Require exit `0`, zero diff-check errors, exactly two changed paths, postimage hashes, and release of only the owned batch lease.
