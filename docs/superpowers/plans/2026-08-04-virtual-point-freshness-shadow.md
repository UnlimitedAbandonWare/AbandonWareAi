# Virtual-Point Freshness Shadow Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add deterministic, redacted freshness evidence for matched historical virtual points while preserving the current constitutional BLOCK boundary and all production decisions.

**Architecture:** Extend only the existing `RagFailureBlackboxService` virtual-point trace seam. The service will calculate an observation-only age from `VirtualPoint.seenAtMs` and emit four bounded keys that accurately describe current legacy behavior; no TTL, benefit score, new risk owner, or allow path is introduced. Freshness enforcement and expected-utility promotion remain a separate, blocked project until calibration ownership and units exist.

**Tech Stack:** Java 17-compatible source, Spring Boot existing repository version, JUnit 5, Spring `ReflectionTestUtils`, Gradle wrapper, request-scoped `TraceStore`.

## Global Constraints

- Execution owner is Desktop; Notebook source mutation is not authorized by this plan.
- Desktop must prove `C:\AbandonWare\demo-1\demo-1\src`, branch ownership, clean target files, and no `.git\index.lock` before editing.
- Modify only:
  - `main/java/com/example/lms/resilience/RagFailureBlackboxService.java`
  - `src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java`
- Preserve `RagConstitutionalScorecard` and `EvidenceAwareGuard` as the non-compensatory BLOCK owner.
- Preserve existing virtual-point matching, score boosting, labels, restore action, and `historyCorrectionRequireCurrentSignal` behavior.
- Do not add `max-age-ms`, TTL, benefit, expected-utility, CVaR, domain multiplier, public API, DB, provider, credential, or environment-variable behavior in this plan.
- The contact-lens 5–10 minute example must not appear as a source constant or production threshold.
- Do not touch `PromptBuilder`, inactive mirrors, archives, generated output, or any other `RiskScorer`.
- Keep every `dev.langchain4j` dependency exactly at `1.0.1`.
- Trace output must contain no raw query, snippet, key, authorization value, provider payload, or unbounded text.
- Do not commit, push, deploy, or mutate external systems without separate explicit authorization.
- Use `AWX_SPLIT_BUILD_OUTPUTS=1`, `AWX_BUILD_HOST_ID=desktop`, and a Desktop-local Gradle project cache.
- `runtimeLineageVerdict=HOLD` and `desktopFinalProof=evidence_needed` remain in force after this shadow-only patch.

## File Map

- Modify `main/java/com/example/lms/resilience/RagFailureBlackboxService.java`
  - Observe age only after `VirtualPointService.nearest(...)` returns a concrete `VirtualPoint`.
  - Reuse `traceVirtualPointPrior(...)`; do not create a second telemetry service.
- Modify `src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java`
  - Extend the existing applied-prior test.
  - Add one matched-but-not-applied test.
- Verify without modification:
  - `src/test/java/com/example/lms/service/guard/EvidenceAwareGuardScorecardTest.java`
  - `src/test/java/com/example/lms/service/guard/EvidenceAwareGuardFreeModeScorecardTest.java`
- No production file is created.

---

### Task 1: Desktop Preflight and Preimage Gate

**Files:**
- Inspect: `main/java/com/example/lms/resilience/RagFailureBlackboxService.java`
- Inspect: `src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java`

**Interfaces:**
- Consumes: Desktop canonical checkout and the two Notebook-observed SHA-256 baselines.
- Produces: A binary edit/no-edit decision with exact source ownership and preimage evidence.

- [ ] **Step 1: Prove the Desktop checkout and collision gates**

Run in PowerShell:

~~~powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
git rev-parse --show-toplevel
git branch --show-current
git status --short
git worktree list
Test-Path -LiteralPath '.git\index.lock'
Get-ChildItem -LiteralPath '__patch_drop__' -File -Filter '*.patch' -ErrorAction SilentlyContinue |
    Measure-Object |
    Select-Object -ExpandProperty Count
~~~

Expected:

- Git top-level equals the Desktop canonical root.
- Branch ownership is explicit.
- Neither declared target is dirty from another owner.
- `.git\index.lock` is `False`.
- No ambiguous top-level PatchDrop patch is pending.

Stop with `desktop-root-unproven`, `branch-ownership-mismatch`, `dirty-overlap`, `index-lock-present`, or `patch-drop-pending` if any expectation fails.

- [ ] **Step 2: Verify both preimages immediately before editing**

~~~powershell
$serviceHash = (Get-FileHash -Algorithm SHA256 `
    'main\java\com\example\lms\resilience\RagFailureBlackboxService.java').Hash.ToLowerInvariant()
$testHash = (Get-FileHash -Algorithm SHA256 `
    'src\test\java\com\example\lms\resilience\RagFailureBlackboxServiceTest.java').Hash.ToLowerInvariant()

$serviceHash -eq '695c64f50aa2accad7a8be626c9d65462c064b013f04a7b13875a39ffc7d06ac'
$testHash -eq 'f0fec87450a68c3b4fd5a0ec7ca36946f6c1e1709dba0b052c74a4cf05009f2a'
~~~

Expected: both comparisons are `True`.

Stop with `changed-preimage` if either comparison is `False`. Do not substitute another source root or update the baselines without a fresh directive review.

- [ ] **Step 3: Reconfirm the active sourceSet and focused test owner**

~~~powershell
rg -n 'srcDirs|java_clean|main/java|main/resources' build.gradle.kts app\build.gradle.kts
rg -n 'void virtualPointPriorAppliesOnlyAfterCurrentHistorySignal|private Snapshot applyVirtualPointPrior|private static void traceVirtualPointPrior' `
    main\java\com\example\lms\resilience\RagFailureBlackboxService.java `
    src\test\java\com\example\lms\resilience\RagFailureBlackboxServiceTest.java
~~~

Expected: root `main/java` and `main/resources`, `:app` `java_clean/resources`, and all three named seams are present.

- [ ] **Step 4: Record a non-commit checkpoint**

~~~powershell
git diff --check -- `
    main/java/com/example/lms/resilience/RagFailureBlackboxService.java `
    src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java
~~~

Expected: exit code `0` and no output. Do not commit.

---

### Task 2: Write the RED Freshness-Trace Tests

**Files:**
- Modify: `src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java:955`
- Test: `src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java`

**Interfaces:**
- Consumes: Existing `VirtualPoint`, `VirtualPointService`, `TraceStore`, `service(...)`, and current-signal fixture.
- Produces: Exact required trace-key contract:
  - `blackbox.risk.virtualPoint.ageKnown: Boolean`
  - `blackbox.risk.virtualPoint.ageMs: Long`, with `-1` reserved for invalid/unknown time
  - `blackbox.risk.virtualPoint.freshnessDisposition: "shadow_uncalibrated"`
  - `blackbox.risk.virtualPoint.decisionAuthority: "legacy_applied" | "not_applied"`

- [ ] **Step 1: Extend the existing applied-prior test with failing assertions**

In `virtualPointPriorAppliesOnlyAfterCurrentHistorySignal()`, immediately after the existing `priorPatternId` assertion, add:

~~~java
Object observedAgeMs = TraceStore.get("blackbox.risk.virtualPoint.ageMs");
assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.virtualPoint.ageKnown"));
assertTrue(observedAgeMs instanceof Number);
assertTrue(((Number) observedAgeMs).longValue() > 0L);
assertEquals("shadow_uncalibrated",
        TraceStore.get("blackbox.risk.virtualPoint.freshnessDisposition"));
assertEquals("legacy_applied",
        TraceStore.get("blackbox.risk.virtualPoint.decisionAuthority"));
~~~

This test deliberately uses the existing `seenAtMs=1L` fixture. It characterizes that the legacy prior still applies while exposing its age; it does not endorse that behavior.

- [ ] **Step 2: Add the matched-but-not-applied RED test**

Insert this test immediately after `virtualPointPriorAppliesOnlyAfterCurrentHistorySignal()`:

~~~java
@Test
void virtualPointBelowThresholdReportsShadowAgeWithoutAuthority() {
    VirtualPointService virtualPoints = new VirtualPointService();
    virtualPoints.put("blackbox:prior", new VirtualPoint(
            new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            0.50d,
            0.50d,
            "context_contamination",
            "vector_quarantine",
            "prior-pattern",
            1L));
    RagFailureBlackboxService service = service(true, null, virtualPoints);
    ReflectionTestUtils.setField(service, "virtualPointEnabled", true);
    ReflectionTestUtils.setField(service, "virtualPointMinSimilarity", 0.92d);
    TraceStore.put("webSearch.returnedCount", 5);
    TraceStore.put("prompt.memory.compressor.activated", true);
    TraceStore.put("prompt.memory.compressor.contaminationScore", 0.50d);

    service.refresh("prior-below-threshold");

    Object observedAgeMs = TraceStore.get("blackbox.risk.virtualPoint.ageMs");
    assertEquals("prior_below_threshold",
            TraceStore.get("blackbox.risk.virtualPoint.reason"));
    assertEquals(Boolean.FALSE,
            TraceStore.get("blackbox.risk.virtualPoint.applied"));
    assertEquals(Boolean.TRUE,
            TraceStore.get("blackbox.risk.virtualPoint.ageKnown"));
    assertTrue(observedAgeMs instanceof Number);
    assertTrue(((Number) observedAgeMs).longValue() > 0L);
    assertEquals("shadow_uncalibrated",
            TraceStore.get("blackbox.risk.virtualPoint.freshnessDisposition"));
    assertEquals("not_applied",
            TraceStore.get("blackbox.risk.virtualPoint.decisionAuthority"));
}
~~~

- [ ] **Step 3: Run only the first RED test**

~~~powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop'
$pcd = Join-Path $env:LOCALAPPDATA 'awx-gradle-project-cache\desktop-risk-utility'
New-Item -ItemType Directory -Force -Path $pcd | Out-Null

.\gradlew.bat test `
    --tests 'com.example.lms.resilience.RagFailureBlackboxServiceTest.virtualPointPriorAppliesOnlyAfterCurrentHistorySignal' `
    --no-daemon `
    --project-cache-dir $pcd
~~~

Expected: `FAIL` because `blackbox.risk.virtualPoint.ageKnown` is absent/null. A compilation error or unrelated test failure is not the intended RED; classify it separately and stop.

- [ ] **Step 4: Run only the second RED test**

~~~powershell
.\gradlew.bat test `
    --tests 'com.example.lms.resilience.RagFailureBlackboxServiceTest.virtualPointBelowThresholdReportsShadowAgeWithoutAuthority' `
    --no-daemon `
    --project-cache-dir $pcd
~~~

Expected: `FAIL` for the same missing freshness-trace contract, while the existing `prior_below_threshold` and `applied=false` assertions remain valid.

- [ ] **Step 5: Record the RED evidence without committing**

Save only command, exit code, failing assertion name, test count, and redacted reason code in the Desktop work log. Do not store raw trace/query contents and do not commit.

---

### Task 3: Implement the Minimal Shadow Trace

**Files:**
- Modify: `main/java/com/example/lms/resilience/RagFailureBlackboxService.java:599-689`
- Modify: `main/java/com/example/lms/resilience/RagFailureBlackboxService.java:1217-1231`
- Test: `src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java`

**Interfaces:**
- Consumes: `VirtualPoint.seenAtMs`, `System.currentTimeMillis()`, and the existing `traceVirtualPointPrior(...)` call path.
- Produces:
  - `private static long virtualPointAgeMs(VirtualPoint point, long nowMs)`
  - An overload of `traceVirtualPointPrior(...)` that accepts `VirtualPoint point`
  - Four additive, redacted TraceStore keys
- Does not produce a freshness allow/block decision.

- [ ] **Step 1: Add the deterministic age helper**

Immediately before `traceVirtualPointPrior(...)`, add:

~~~java
private static long virtualPointAgeMs(VirtualPoint point, long nowMs) {
    if (point == null || point.seenAtMs <= 0L || nowMs < point.seenAtMs) {
        return -1L;
    }
    return nowMs - point.seenAtMs;
}
~~~

`-1L` is the only unknown/invalid sentinel. Do not add minute buckets or a 5–10 minute constant.

- [ ] **Step 2: Preserve the existing five-argument trace API as an internal delegator**

Replace the current five-argument method header/body with this delegating shape, retaining the existing trace writes inside the new overload:

~~~java
private static void traceVirtualPointPrior(boolean matched,
                                           double similarity,
                                           String priorPatternId,
                                           boolean applied,
                                           String reason) {
    traceVirtualPointPrior(matched, similarity, priorPatternId, applied, reason, null);
}

private static void traceVirtualPointPrior(boolean matched,
                                           double similarity,
                                           String priorPatternId,
                                           boolean applied,
                                           String reason,
                                           VirtualPoint point) {
    try {
        TraceStore.put(PREFIX + "virtualPoint.matched", matched);
        TraceStore.put(PREFIX + "virtualPoint.similarity", round4(similarity));
        TraceStore.put(PREFIX + "virtualPoint.priorPatternId", safeLabel(priorPatternId, ""));
        TraceStore.put(PREFIX + "virtualPoint.applied", applied);
        TraceStore.put(PREFIX + "virtualPoint.reason", safePublicLabel(reason, "none"));
        if (point != null) {
            long ageMs = virtualPointAgeMs(point, System.currentTimeMillis());
            TraceStore.put(PREFIX + "virtualPoint.ageKnown", ageMs >= 0L);
            TraceStore.put(PREFIX + "virtualPoint.ageMs", ageMs);
            TraceStore.put(PREFIX + "virtualPoint.freshnessDisposition", "shadow_uncalibrated");
            TraceStore.put(PREFIX + "virtualPoint.decisionAuthority",
                    applied ? "legacy_applied" : "not_applied");
        }
    } catch (Throwable t) {
        traceSkipped("virtual_point_prior_trace", reason, t);
    }
}
~~~

Do not log `point`, its vector, or the current trace map.

- [ ] **Step 3: Pass the matched point into every post-match disposition**

Change exactly these four calls inside `applyVirtualPointPrior(...)`:

~~~java
traceVirtualPointPrior(true, prior.similarity(), point.patternId, false,
        "prior_below_threshold", point);

traceVirtualPointPrior(true, prior.similarity(), point.patternId, false,
        "prior_observe_only", point);

traceVirtualPointPrior(true, prior.similarity(), point.patternId, false,
        "boost_below_threshold", point);

traceVirtualPointPrior(true, prior.similarity(), point.patternId, true,
        "virtual_point_prior", point);
~~~

Leave pre-match calls such as `history_correction_disabled`, `current_signal_required`, `empty_vector`, `no_prior_match`, and `prior_lookup_failed` on the five-argument overload because no matched point exists.

- [ ] **Step 4: Run both focused tests and verify GREEN**

~~~powershell
.\gradlew.bat test `
    --tests 'com.example.lms.resilience.RagFailureBlackboxServiceTest.virtualPointPriorAppliesOnlyAfterCurrentHistorySignal' `
    --tests 'com.example.lms.resilience.RagFailureBlackboxServiceTest.virtualPointBelowThresholdReportsShadowAgeWithoutAuthority' `
    --no-daemon `
    --project-cache-dir $pcd
~~~

Expected: both tests `PASS`.

- [ ] **Step 5: Run the entire focused test class**

~~~powershell
.\gradlew.bat test `
    --tests 'com.example.lms.resilience.RagFailureBlackboxServiceTest' `
    --no-daemon `
    --project-cache-dir $pcd
~~~

Expected: all tests in the class pass, including existing current-signal, graph, redaction, scorecard, and memory-write protections.

- [ ] **Step 6: Inspect the exact diff and keep it uncommitted**

~~~powershell
git diff --check -- `
    main/java/com/example/lms/resilience/RagFailureBlackboxService.java `
    src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java

git diff --stat -- `
    main/java/com/example/lms/resilience/RagFailureBlackboxService.java `
    src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java
~~~

Expected: no whitespace errors and exactly two modified files. Do not commit.

---

### Task 4: Prove BLOCK Invariance and Scope Containment

**Files:**
- Verify: `src/test/java/com/example/lms/service/guard/EvidenceAwareGuardScorecardTest.java`
- Verify: `src/test/java/com/example/lms/service/guard/EvidenceAwareGuardFreeModeScorecardTest.java`
- Inspect: the two declared modified files

**Interfaces:**
- Consumes: Existing scorecard BLOCK trace and the additive freshness keys.
- Produces: Evidence that new telemetry does not create or relax an allow path.

- [ ] **Step 1: Run both existing guard suites**

~~~powershell
.\gradlew.bat test `
    --tests 'com.example.lms.service.guard.EvidenceAwareGuardScorecardTest' `
    --tests 'com.example.lms.service.guard.EvidenceAwareGuardFreeModeScorecardTest' `
    --no-daemon `
    --project-cache-dir $pcd
~~~

Expected: all tests pass; `blackbox.risk.blockRecommended=true` remains authoritative.

- [ ] **Step 2: Prove no enforcement or utility surface was added**

~~~powershell
$targets = @(
    'main\java\com\example\lms\resilience\RagFailureBlackboxService.java',
    'src\test\java\com\example\lms\resilience\RagFailureBlackboxServiceTest.java'
)
$forbidden = 'max-age|maxAge|expectedUtility|expectedNetUtility|benefitLowerBound|harmUpperBound|domainMultiplier|CvarAggregator'
$forbiddenCount = @(Select-String -LiteralPath $targets -Pattern $forbidden).Count
$forbiddenCount
~~~

Expected: `0`.

- [ ] **Step 3: Prove all four trace keys and both authority values are covered**

~~~powershell
rg -n 'virtualPoint\.(ageKnown|ageMs|freshnessDisposition|decisionAuthority)|shadow_uncalibrated|legacy_applied|not_applied' `
    main\java\com\example\lms\resilience\RagFailureBlackboxService.java `
    src\test\java\com\example\lms\resilience\RagFailureBlackboxServiceTest.java
~~~

Expected: every key is present in the service and asserted in the focused tests; both authority values are tested.

- [ ] **Step 4: Run a count-only secret scan on the declared diff**

~~~powershell
$diff = git diff -- `
    main/java/com/example/lms/resilience/RagFailureBlackboxService.java `
    src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java

$secretPattern = '(?i)(sk-[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}|Bearer\s+[A-Za-z0-9._-]{20,}|api[_-]?key\s*[:=]\s*["''][^"'']{8,}["''])'
[regex]::Matches(($diff -join "`n"), $secretPattern).Count
~~~

Expected: `0`. Do not print matching material if the count is nonzero; stop with `secret-leak-risk`.

---

### Task 5: Desktop Build Evidence and Handoff

**Files:**
- Verify: root and `:app` active build outputs
- Record: Desktop-local redacted verification log only

**Interfaces:**
- Consumes: Green focused tests, unchanged BLOCK tests, and contained two-file diff.
- Produces: Desktop supporting build evidence; no runtime/provider success claim.

- [ ] **Step 1: Run build gates sequentially**

~~~powershell
.\gradlew.bat projects --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat :app:classes --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar --no-daemon --project-cache-dir $pcd
~~~

Expected: each command exits `0`. Stop on the first failure and classify `gradle-task-failed`, `wrong-sourceset`, `langchain4j-version-mismatch`, or the concrete compiler/test reason.

- [ ] **Step 2: Record postimage hashes and final target status**

~~~powershell
Get-FileHash -Algorithm SHA256 `
    'main\java\com\example\lms\resilience\RagFailureBlackboxService.java',
    'src\test\java\com\example\lms\resilience\RagFailureBlackboxServiceTest.java'

git status --short -- `
    main/java/com/example/lms/resilience/RagFailureBlackboxService.java `
    src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java
~~~

Expected: exactly the two declared files are modified and both postimage hashes are recorded.

- [ ] **Step 3: Preserve the runtime proof boundary**

Record:

~~~text
runtimeLineageVerdict=HOLD
desktopFinalProof=evidence_needed
freshnessEnforcement=not-authorized
expectedUtilityPromotion=not-authorized
~~~

This shadow patch proves telemetry and regression safety only. It does not prove provider lineage, production usefulness, freshness thresholds, or expected utility.

- [ ] **Step 4: Stop without commit or deployment**

Do not commit, push, deploy, activate a TTL, edit `application.yml`, or send an external message. Present the two-file diff, RED/GREEN output, build output, secret count, and postimage hashes for explicit apply/commit authorization.

## Self-Review Results

- Spec coverage: The plan covers the confirmed `seenAtMs` gap, accurate legacy authority evidence, current-signal preservation, redaction, BLOCK invariance, rollback evidence, and Desktop build gates.
- Deliberately excluded: freshness enforcement, domain-risk multiplier, benefit estimation, expected utility, runtime agent creation, and production threshold activation. Each requires a separate calibrated contract and therefore is not silently approximated.
- Placeholder scan: No forbidden placeholder token or undefined implementation method remains.
- Type consistency:
  - `VirtualPoint.seenAtMs` is `long`.
  - `virtualPointAgeMs(...)` returns `long`.
  - Trace types are Boolean, Long, and bounded constant labels.
  - The five-argument trace helper remains available for pre-match paths; the six-argument overload is used only after a concrete match.
- Rollback: Restore only the two Task 1 preimages, rerun the focused class and guard suites, and leave unrelated work untouched.
