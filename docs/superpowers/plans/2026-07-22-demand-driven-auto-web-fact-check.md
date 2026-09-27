# Demand-Driven AUTO Web Fact-Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route explicit web fact-check requests through the existing AUTO web/RAG path while keeping ordinary chat local.

**Architecture:** Extend only `SearchDecisionService` with deterministic intent helpers. Reuse `ChatApiController.shouldUseWebForSearchMode`, `AnswerQualityEvaluator`, and `EvidenceRepairHandler`; do not create a second retry or orchestration path.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Gradle 8.7.

## Global Constraints

- Patch active sourceSets only.
- Preserve all `dev.langchain4j:*` dependencies at `1.0.1`.
- Keep final prompt construction at `PromptBuilder.build(PromptContext)`.
- Keep provider failures fail-soft and diagnostics redacted.
- Do not enable always-on Browser, Computer, Supabase, producer, or ensemble calls.

---

### Task 1: Characterize Explicit AUTO Web Intent

**Files:**
- Modify: `src/test/java/com/example/lms/gptsearch/decision/SearchDecisionServiceTest.java`
- Modify: `src/test/java/com/example/lms/api/ChatApiControllerAutoSearchDecisionTest.java`

**Interfaces:**
- Consumes: `SearchDecisionService.decide(String, SearchMode, List<String>, Integer)`
- Produces: executable behavior requirements for `AUTO` depth and top-level web gating

- [ ] **Step 1: Add the failing decision tests**

```java
@Test
void autoModeDeepSearchesExplicitKoreanWebFactCheckIntent() {
    SearchDecision decision = new SearchDecisionService().decide(
            "모르는 내용은 웹에서 찾아 사실관계를 교차 검증해줘.",
            SearchMode.AUTO, null, 5);
    assertTrue(decision.shouldSearch());
    assertEquals(SearchDecision.Depth.DEEP, decision.depth());
}

@Test
void autoModeSkipsIncidentalWebAndRagMention() {
    SearchDecision decision = new SearchDecisionService().decide(
            "웹과 RAG 상태를 로컬에서 설명해줘.",
            SearchMode.AUTO, null, 5);
    assertFalse(decision.shouldSearch());
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "*SearchDecisionServiceTest" --tests "*ChatApiControllerAutoSearchDecisionTest" --no-daemon --project-cache-dir $pcd
```

Expected: the explicit Korean fact-check assertion fails because the current heuristic returns no-search.

### Task 2: Implement The Narrow AUTO Policy

**Files:**
- Modify: `main/java/com/example/lms/gptsearch/decision/SearchDecisionService.java`

**Interfaces:**
- Consumes: normalized user query text
- Produces: existing `SearchDecision` with `LIGHT` or `DEEP`; no new public API

- [ ] **Step 1: Add private explicit lookup and verification helpers**

The helpers must recognize Korean web lookup verbs and English `search the web`, `browse the web`, or online lookup phrases. Verification markers include fact-check and cross-validation wording.

- [ ] **Step 2: Route AUTO deterministically**

Return `DEEP` when lookup and verification are both explicit; return `LIGHT` for lookup alone; preserve comparison, question, and no-search behavior.

- [ ] **Step 3: Run focused GREEN tests**

Run the Task 1 command and require zero failures.

- [ ] **Step 4: Run adjacent quality/repair tests**

```powershell
.\gradlew.bat test --tests "*AnswerQualityEvaluatorTest" --tests "*EvidenceRepairHandlerHonestyTest" --no-daemon --project-cache-dir $pcd
```

Expected: zero failures and no new retry path.

### Task 3: Runtime And Safety Proof

**Files:**
- No source changes expected

**Interfaces:**
- Consumes: current bootJar and localhost Browser session
- Produces: observed AUTO/RAG route, fail-soft reason, and final answer state

- [ ] **Step 1: Build the current source with isolated Desktop outputs**

```powershell
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
```

- [ ] **Step 2: Run one explicit web fact-check chat and one local-chat control**

Expected: the explicit request enters the web route; the local control remains no-search. Missing providers must produce redacted evidence-needed/fail-soft output.

- [ ] **Step 3: Run count-only secret and source-set gates**

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

Expected: both tasks succeed; changed-file secret hits remain zero.

