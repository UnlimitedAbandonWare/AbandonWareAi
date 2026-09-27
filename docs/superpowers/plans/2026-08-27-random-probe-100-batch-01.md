# Citation Gate Official-Source Correspondence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Repository-local rules keep subagents read-only.

**Goal:** Prevent the active ensemble legacy citation gate from accepting an unrelated or blank `official` entry as official coverage for a non-official source set.

**Architecture:** Keep the existing `CitationGate.check(sources, official)` boundary and count gate. When official evidence is required, add one private exact-membership check over trimmed, nonblank strings; the gate passes only when at least one official locator is also present in the already canonicalized source list. Preserve count-only/redacted traces and add a distinct mismatch reason.

**Tech Stack:** Java 17, JUnit 5, Gradle 8.7, Spring `TraceStore` contract.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `P1-CIT-001`, and root [AGENTS.md](../../../AGENTS.md).

## Global Constraints

- Modify exactly `main/java/com/example/lms/guard/CitationGate.java` and `src/test/java/com/example/lms/guard/LegacyGuardTraceTest.java`.
- Keep final prompt construction and all provider, secret, LangChain4j, and Spring property contracts unchanged.
- Write and run the failing test before production code.
- Source edit requires a frozen three-way preflight with stable `APPLY`, an owned source-edit lease, and unchanged preimage hashes `2d59b39355ee759e158677b1991459732f427b5be458a57e868bed77a389a7ca` and `bb548eb1308807a9d3565af33d40649951da5c44134f32fbc0b5b6cecd207ac3`.
- Do not commit, push, deploy, or modify unrelated dirty files.

---

### Task 1: Add the official-to-source correspondence regression

**Files:**

- Modify: `src/test/java/com/example/lms/guard/LegacyGuardTraceTest.java`
- Test: `src/test/java/com/example/lms/guard/LegacyGuardTraceTest.java`

**Interfaces:**

- Consumes: `CitationGate(int minCount, boolean requireOfficial)` and `check(List<String> sources, List<String> official)`.
- Produces: a real-behavior regression that fails when the membership branch is absent and observes only redacted reason/count traces.

- [x] **Step 1: Add the failing behavior test**

Add this method to `LegacyGuardTraceTest`:

```java
@Test
void legacyCitationGateRejectsOfficialListWithNoSourceOverlap() {
    CitationGate gate = new CitationGate(2, true);

    assertFalse(gate.check(
            List.of("https://community.example/a", "https://community.example/b"),
            List.of("https://developers.openai.com/unrelated")));

    assertEquals("official_source_mismatch", TraceStore.get("guard.legacyCitation.reason"));
    assertEquals(Boolean.FALSE, TraceStore.get("gate.citation.passed"));
}
```

Mutation check: deleting the new official/source membership branch makes this test return `true` and fail; the expected values are literal and do not reuse production normalization.

- [x] **Step 2: Run RED and verify the failure reason**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.guard.LegacyGuardTraceTest.legacyCitationGateRejectsOfficialListWithNoSourceOverlap" --no-daemon --console=plain --project-cache-dir C:\Users\nninn\.awx-gradle-project-cache\desktop-random-probe-100
```

Expected: the assertion on `assertFalse` fails because the current count-only implementation returns `true`. A compilation/setup/cache failure is not valid RED evidence.

### Task 2: Require one real official/source intersection

**Files:**

- Modify: `main/java/com/example/lms/guard/CitationGate.java`
- Test: `src/test/java/com/example/lms/guard/LegacyGuardTraceTest.java`

**Interfaces:**

- Consumes: the two existing source lists after upstream URL canonicalization.
- Produces: `true` only when the source-count gate passes and, when required, at least one trimmed nonblank official string exactly equals a trimmed nonblank source string.

- [x] **Step 1: Replace the count-only official branch**

Replace:

```java
if (requireOfficial && officialCount == 0) {
    traceDecision(false, "official_required", sourceCount, officialCount);
    return false;
}
```

with:

```java
if (requireOfficial && officialCount == 0) {
    traceDecision(false, "official_required", sourceCount, officialCount);
    return false;
}
if (requireOfficial && !containsOfficialSource(sources, official)) {
    traceDecision(false, "official_source_mismatch", sourceCount, officialCount);
    return false;
}
```

and add:

```java
private static boolean containsOfficialSource(List<String> sources, List<String> official) {
    for (String officialSource : official) {
        if (officialSource == null || officialSource.isBlank()) {
            continue;
        }
        String expected = officialSource.trim();
        for (String source : sources) {
            if (source != null && !source.isBlank() && expected.equals(source.trim())) {
                return true;
            }
        }
    }
    return false;
}
```

- [x] **Step 2: Run GREEN**

Run the exact RED command again. Expected: the selected test passes.

- [x] **Step 3: Run the focused citation boundary**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.guard.LegacyGuardTraceTest" --tests "com.example.lms.service.ChatWorkflowEnsembleCitationSourceWiringTest" --tests "com.example.lms.ensemble.DiverseSamplingOrchestratorTest" --no-daemon --console=plain --project-cache-dir C:\Users\nninn\.awx-gradle-project-cache\desktop-random-probe-100
```

Expected: all selected citation/ensemble tests pass with zero failures and errors.

- [x] **Step 4: Run source boundary gates**

Run:

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon --console=plain --project-cache-dir C:\Users\nninn\.awx-gradle-project-cache\desktop-random-probe-100
```

Expected: exit `0`; only the already characterized `inactive-present: app/src/main/java` hygiene message may remain.

- [x] **Step 5: Inspect the exact diff and release the owned lease**

Verify that the diff contains only the new behavior test, membership branch, and private helper. Record postimage SHA-256 values, update the issue ledger to `FIXED`, and release only the batch's matching owner ID.
