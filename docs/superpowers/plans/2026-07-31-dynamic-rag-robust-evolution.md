# Dynamic RAG Robust Evolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backward-compatible Dynamic RAG robustness selector that computes Pareto/lower-tail proposals in `SHADOW`, preserves the current executed plan unless verified `ENFORCE` inputs are injected, and fails closed on incomplete evidence.

**Architecture:** `ExecutionPlanApplier` freezes a request-scoped robustness snapshot from existing redacted traces and passes it to `StrategyConflictResolver`. The resolver keeps the existing fixed-priority plan as the baseline, evaluates injected versioned candidate profiles with Pareto filtering and `CvarAggregator.lowerTailMean`, traces a proposal, and changes the executed mode only for an eligible `ENFORCE` policy. Production starts in `SHADOW` with no profile transport, so current routing behavior remains unchanged until a separately proven profile owner is connected.

**Tech Stack:** Java 17, Spring components, JUnit 5, Gradle Wrapper, `TraceStore`, existing `CvarAggregator`

## Global Constraints

- Source owner is Desktop; Notebook evidence and this plan do not authorize application-source mutation.
- Before Task 1, run `demo1-source-edit-three-way-preflight`; enter a source-owner guard only after a stable `APPLY`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)`.
- Keep all `dev.langchain4j` dependencies exactly at `1.0.1`.
- Keep exactly one primary booster active per request.
- Do not add a retrieval mode, CVaR implementation, orchestration framework, profile store, public API, database mutation, credential mutation, or environment-variable rename.
- Do not modify `CvarAggregator`, `NovaNextFusionService`, `PromptBuilder`, RetrievalOrder aliases, inactive source roots, archives, or generated output.
- Production remains `SHADOW`; profile transport and production `ENFORCE` activation remain a separate evidence-gated follow-up.
- Missing, malformed, stale, undersampled, hash-mismatched, or over-budget evidence must preserve the current fixed-priority plan.
- Do not execute Git commit steps unless the user separately authorizes commits. Without that authorization, record the suggested commit message and stop after verification.
- Desktop verification must isolate `GRADLE_USER_HOME` and `--project-cache-dir` and preserve split build output settings.

## Scope and File Map

This is one testable Phase 1 subsystem: request snapshot, shadow scoring, deterministic selection, and fallback. Profile persistence, Plan DSL schema changes, online provider shadow calls, `ArtPlateEvolver`, and runtime `ENFORCE` configuration are excluded.

| File | Responsibility in this plan |
| --- | --- |
| `main/java/com/example/lms/orchestration/StrategyConflictResolver.java` | Own nested robustness value types, Pareto/lower-tail decision kernel, SHADOW/ENFORCE decision, direct redacted traces, and fixed-priority fallback |
| `main/java/com/example/lms/orchestration/ExecutionPlanApplier.java` | Freeze numeric request evidence, derive a low-cardinality trigger bucket, enforce budget-evidence completeness, and pass one immutable snapshot to the resolver |
| `src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java` | Prove scoring, Pareto dominance, margin, SHADOW/ENFORCE behavior, compatibility, and all 16 trigger combinations |
| `src/test/java/com/example/lms/orchestration/ExecutionPlanApplierTest.java` | Prove trace-to-snapshot mapping, missing/invalid/budget fallback, unchanged SHADOW execution, and redaction |

No new production file is created. The robustness types are package-scoped nested records so the approved four-file boundary and public API remain unchanged.

---

### Task 1: Add the deterministic Pareto/lower-tail scoring kernel

**Files:**
- Modify: `main/java/com/example/lms/orchestration/StrategyConflictResolver.java:7-25`
- Modify: `src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java:12-167`
- Reuse unchanged: `main/java/com/nova/protocol/fusion/CvarAggregator.java:63-74`

**Interfaces:**
- Consumes: `ExecutionPlan.PrimaryMode`, `CvarAggregator.lowerTailMean(List<Double>, double)`
- Produces: nested `RobustnessMode`, `RobustnessVector`, `CandidateProfile`, `RobustnessSnapshot`, `RobustnessPolicy`, and `RobustnessDecision`
- Produces: package-scoped static methods `lowerTailScore`, `paretoSurvivors`, `dominates`, and `decideRobustness`

- [ ] **Step 1: Write failing lower-tail and Pareto tests**

Add the imports:

```java
import java.util.Map;
```

Append these tests and helpers inside `StrategyConflictResolverTest`:

```java
@Test
void paretoSelectorChoosesTheStrongerLowerTailCandidate() {
    StrategyConflictResolver.CandidateProfile baseline = profile(
            ExecutionPlan.PrimaryMode.EXTREMEZ, 0.20d, 0.30d, 0.30d, 0.40d, 30);
    StrategyConflictResolver.CandidateProfile stronger = profile(
            ExecutionPlan.PrimaryMode.OVERDRIVE, 0.70d, 0.80d, 0.75d, 0.65d, 30);
    StrategyConflictResolver.RobustnessPolicy policy = policy(
            StrategyConflictResolver.RobustnessMode.ENFORCE,
            Map.of(baseline.mode(), baseline, stronger.mode(), stronger));

    StrategyConflictResolver.RobustnessDecision decision =
            StrategyConflictResolver.decideRobustness(
                    ExecutionPlan.PrimaryMode.EXTREMEZ,
                    completeSnapshot(),
                    policy);

    assertEquals(ExecutionPlan.PrimaryMode.OVERDRIVE, decision.proposedMode());
    assertEquals(0.70d, decision.proposedScore(), 1.0e-9);
    assertEquals(1, decision.survivorCount());
    assertEquals("enforced", decision.reason());
}

@Test
void invalidAxesCannotImproveALowerTailScore() {
    StrategyConflictResolver.RobustnessVector invalid =
            new StrategyConflictResolver.RobustnessVector(
                    Double.NaN, Double.POSITIVE_INFINITY, -1.0d, 2.0d);

    assertEquals(0.0d, StrategyConflictResolver.lowerTailScore(invalid, 0.25d), 1.0e-9);
}

@Test
void minimumMarginKeepsTheBaselineMode() {
    StrategyConflictResolver.CandidateProfile baseline = profile(
            ExecutionPlan.PrimaryMode.EXTREMEZ, 0.70d, 0.70d, 0.70d, 0.70d, 30);
    StrategyConflictResolver.CandidateProfile nearTie = profile(
            ExecutionPlan.PrimaryMode.OVERDRIVE, 0.74d, 0.74d, 0.74d, 0.74d, 30);
    StrategyConflictResolver.RobustnessPolicy policy = policy(
            StrategyConflictResolver.RobustnessMode.ENFORCE,
            Map.of(baseline.mode(), baseline, nearTie.mode(), nearTie));

    StrategyConflictResolver.RobustnessDecision decision =
            StrategyConflictResolver.decideRobustness(
                    ExecutionPlan.PrimaryMode.EXTREMEZ,
                    completeSnapshot(),
                    policy);

    assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, decision.proposedMode());
    assertEquals("margin-not-met", decision.reason());
}

private static StrategyConflictResolver.CandidateProfile profile(
        ExecutionPlan.PrimaryMode mode,
        double consistency,
        double nonStarvation,
        double authorityFloor,
        double coverage,
        int samples) {
    return new StrategyConflictResolver.CandidateProfile(
            mode,
            new StrategyConflictResolver.RobustnessVector(
                    consistency, nonStarvation, authorityFloor, coverage),
            samples,
            "profile-v1",
            false,
            true);
}

private static StrategyConflictResolver.RobustnessPolicy policy(
        StrategyConflictResolver.RobustnessMode mode,
        Map<ExecutionPlan.PrimaryMode, StrategyConflictResolver.CandidateProfile> profiles) {
    return new StrategyConflictResolver.RobustnessPolicy(
            mode,
            "r1a1c1t0",
            "profile-v1",
            profiles,
            30,
            0.25d,
            0.05d);
}

private static StrategyConflictResolver.RobustnessSnapshot completeSnapshot() {
    return new StrategyConflictResolver.RobustnessSnapshot(
            "r1a1c1t0", 0.50d, 0.25d, 0.20d, false, true, true, true);
}
```

- [ ] **Step 2: Run the focused test to verify RED**

Run from the Desktop canonical root with an isolated project cache:

```powershell
.\gradlew.bat test --tests "com.example.lms.orchestration.StrategyConflictResolverTest" --no-daemon --project-cache-dir $pcd
```

Expected: compilation fails because `CandidateProfile`, `RobustnessPolicy`, `RobustnessSnapshot`, `RobustnessDecision`, `RobustnessMode`, `lowerTailScore`, and `decideRobustness` do not exist.

- [ ] **Step 3: Add the nested value types and pure scoring functions**

Add `import com.nova.protocol.fusion.CvarAggregator;`, `import java.util.Comparator;`, and the following members inside `StrategyConflictResolver` after `Signals`:

```java
enum RobustnessMode {
    OFF,
    SHADOW,
    ENFORCE
}

record RobustnessVector(
        double consistency,
        double nonStarvation,
        double authorityFloor,
        double coverage) {
    RobustnessVector {
        consistency = score(consistency);
        nonStarvation = score(nonStarvation);
        authorityFloor = score(authorityFloor);
        coverage = score(coverage);
    }

    List<Double> axes() {
        return List.of(consistency, nonStarvation, authorityFloor, coverage);
    }
}

record CandidateProfile(
        ExecutionPlan.PrimaryMode mode,
        RobustnessVector vector,
        int sampleCount,
        String profileHash,
        boolean stale,
        boolean budgetEligible) {
    CandidateProfile {
        mode = mode == null ? ExecutionPlan.PrimaryMode.NORMAL : mode;
        vector = vector == null ? new RobustnessVector(0.0d, 0.0d, 0.0d, 0.0d) : vector;
        sampleCount = Math.max(0, sampleCount);
        profileHash = safeToken(profileHash);
    }
}

record RobustnessSnapshot(
        String bucket,
        double coverageScore,
        double authorityScore,
        double consistencyScore,
        boolean highRiskTail,
        boolean budgetEligible,
        boolean completeEvidence,
        boolean validMetrics) {
    RobustnessSnapshot {
        bucket = safeToken(bucket);
        coverageScore = score(coverageScore);
        authorityScore = score(authorityScore);
        consistencyScore = score(consistencyScore);
    }

    static RobustnessSnapshot missing() {
        return new RobustnessSnapshot(
                "missing", 0.0d, 0.0d, 0.0d, false, false, false, false);
    }
}

record RobustnessPolicy(
        RobustnessMode mode,
        String bucket,
        String profileHash,
        Map<ExecutionPlan.PrimaryMode, CandidateProfile> profiles,
        int minimumSamples,
        double tailFraction,
        double minimumImprovement) {
    RobustnessPolicy {
        mode = mode == null ? RobustnessMode.SHADOW : mode;
        bucket = safeToken(bucket);
        profileHash = safeToken(profileHash);
        profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
        minimumSamples = Math.max(1, minimumSamples);
        tailFraction = clamp(tailFraction, 0.01d, 1.0d);
        minimumImprovement = clamp(minimumImprovement, 0.0d, 1.0d);
    }

    static RobustnessPolicy shadowWithoutProfile() {
        return new RobustnessPolicy(
                RobustnessMode.SHADOW, "missing", "missing", Map.of(), 30, 0.25d, 0.05d);
    }
}

record RobustnessDecision(
        RobustnessMode mode,
        ExecutionPlan.PrimaryMode baselineMode,
        ExecutionPlan.PrimaryMode proposedMode,
        double baselineScore,
        double proposedScore,
        int candidateCount,
        int survivorCount,
        String profileHash,
        String reason,
        boolean enforced) {
    ExecutionPlan.PrimaryMode executedMode() {
        return enforced ? proposedMode : baselineMode;
    }
}

static double lowerTailScore(RobustnessVector vector, double tailFraction) {
    RobustnessVector safe = vector == null
            ? new RobustnessVector(0.0d, 0.0d, 0.0d, 0.0d)
            : vector;
    return CvarAggregator.lowerTailMean(safe.axes(), clamp(tailFraction, 0.01d, 1.0d));
}

static boolean dominates(CandidateProfile left, CandidateProfile right) {
    RobustnessVector a = left.vector();
    RobustnessVector b = right.vector();
    boolean noWorse = a.consistency() >= b.consistency()
            && a.nonStarvation() >= b.nonStarvation()
            && a.authorityFloor() >= b.authorityFloor()
            && a.coverage() >= b.coverage();
    boolean strictlyBetter = a.consistency() > b.consistency()
            || a.nonStarvation() > b.nonStarvation()
            || a.authorityFloor() > b.authorityFloor()
            || a.coverage() > b.coverage();
    return noWorse && strictlyBetter;
}

static List<CandidateProfile> paretoSurvivors(List<CandidateProfile> candidates) {
    List<CandidateProfile> safe = candidates == null ? List.of() : List.copyOf(candidates);
    return safe.stream()
            .filter(candidate -> safe.stream().noneMatch(other -> other != candidate && dominates(other, candidate)))
            .toList();
}

private static double score(double value) {
    return Double.isFinite(value) ? clamp(value, 0.0d, 1.0d) : 0.0d;
}

private static double clamp(double value, double min, double max) {
    if (!Double.isFinite(value)) {
        return min;
    }
    return Math.max(min, Math.min(max, value));
}

private static String safeToken(String value) {
    if (value == null || value.isBlank()) {
        return "missing";
    }
    String normalized = value.replaceAll("[^A-Za-z0-9._-]", "_");
    return normalized.substring(0, Math.min(80, normalized.length()));
}
```

Add this decision function after `paretoSurvivors`:

```java
static RobustnessDecision decideRobustness(
        ExecutionPlan.PrimaryMode baselineMode,
        RobustnessSnapshot snapshot,
        RobustnessPolicy policy) {
    ExecutionPlan.PrimaryMode baseline = baselineMode == null
            ? ExecutionPlan.PrimaryMode.NORMAL
            : baselineMode;
    RobustnessPolicy safePolicy = policy == null
            ? RobustnessPolicy.shadowWithoutProfile()
            : policy;
    RobustnessSnapshot safeSnapshot = snapshot == null
            ? RobustnessSnapshot.missing()
            : snapshot;
    int candidateCount = safePolicy.profiles().size();

    if (safePolicy.mode() == RobustnessMode.OFF) {
        return fallback(safePolicy, baseline, candidateCount, "mode-off");
    }
    if (!safeSnapshot.completeEvidence()) {
        return fallback(safePolicy, baseline, candidateCount, "snapshot-incomplete");
    }
    if (!safeSnapshot.validMetrics()) {
        return fallback(safePolicy, baseline, candidateCount, "invalid-metric");
    }
    if (!safeSnapshot.budgetEligible()) {
        return fallback(safePolicy, baseline, candidateCount, "budget-exceeded");
    }
    if (!safePolicy.bucket().equals(safeSnapshot.bucket())) {
        return fallback(safePolicy, baseline, candidateCount, "profile-missing");
    }
    if (safePolicy.profiles().isEmpty()) {
        return fallback(safePolicy, baseline, 0, "profile-missing");
    }

    List<CandidateProfile> sameHash = safePolicy.profiles().values().stream()
            .filter(profile -> safePolicy.profileHash().equals(profile.profileHash()))
            .toList();
    if (sameHash.isEmpty() || sameHash.stream().anyMatch(CandidateProfile::stale)) {
        return fallback(safePolicy, baseline, candidateCount, "profile-stale");
    }
    List<CandidateProfile> sampled = sameHash.stream()
            .filter(profile -> profile.sampleCount() >= safePolicy.minimumSamples())
            .toList();
    if (sampled.isEmpty()) {
        return fallback(safePolicy, baseline, candidateCount, "insufficient-samples");
    }
    List<CandidateProfile> budgeted = sampled.stream()
            .filter(CandidateProfile::budgetEligible)
            .toList();
    if (budgeted.isEmpty()) {
        return fallback(safePolicy, baseline, candidateCount, "budget-exceeded");
    }

    CandidateProfile baselineProfile = budgeted.stream()
            .filter(profile -> profile.mode() == baseline)
            .findFirst()
            .orElse(null);
    if (baselineProfile == null) {
        return fallback(safePolicy, baseline, candidateCount, "baseline-profile-missing");
    }
    List<CandidateProfile> survivors = paretoSurvivors(budgeted);
    if (survivors.isEmpty()) {
        return fallback(safePolicy, baseline, candidateCount, "no-pareto-survivor");
    }

    CandidateProfile best = survivors.stream()
            .sorted(Comparator
                    .comparingDouble((CandidateProfile profile) ->
                            lowerTailScore(profile.vector(), safePolicy.tailFraction()))
                    .reversed()
                    .thenComparing(profile -> profile.mode().name()))
            .findFirst()
            .orElse(baselineProfile);
    double baselineScore = lowerTailScore(baselineProfile.vector(), safePolicy.tailFraction());
    double proposedScore = lowerTailScore(best.vector(), safePolicy.tailFraction());
    if (best.mode() == baseline
            || proposedScore - baselineScore < safePolicy.minimumImprovement()) {
        return new RobustnessDecision(
                safePolicy.mode(), baseline, baseline, baselineScore, baselineScore,
                candidateCount, survivors.size(), safePolicy.profileHash(), "margin-not-met", false);
    }

    boolean enforced = safePolicy.mode() == RobustnessMode.ENFORCE;
    return new RobustnessDecision(
            safePolicy.mode(), baseline, best.mode(), baselineScore, proposedScore,
            candidateCount, survivors.size(), safePolicy.profileHash(),
            enforced ? "enforced" : "shadow-proposal", enforced);
}

private static RobustnessDecision fallback(
        RobustnessPolicy policy,
        ExecutionPlan.PrimaryMode baseline,
        int candidateCount,
        String reason) {
    return new RobustnessDecision(
            policy.mode(), baseline, baseline, 0.0d, 0.0d,
            candidateCount, 0, policy.profileHash(), reason, false);
}
```

- [ ] **Step 4: Run focused tests to verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.orchestration.StrategyConflictResolverTest" --no-daemon --project-cache-dir $pcd
```

Expected: all existing and new resolver tests pass; `NovaCvarAggregatorTest.lowerTailMeanClampsInvalidScoresForRiskDiagnostics` remains unchanged and will be run in Task 5.

- [ ] **Step 5: Record or create the task commit**

Suggested commit: `feat: add robust lower-tail strategy scoring`

If commits are separately authorized:

```powershell
git add main/java/com/example/lms/orchestration/StrategyConflictResolver.java src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java
git commit -m "feat: add robust lower-tail strategy scoring"
```

---

### Task 2: Integrate OFF/SHADOW/ENFORCE without changing the public API

**Files:**
- Modify: `main/java/com/example/lms/orchestration/StrategyConflictResolver.java:27-167`
- Modify: `src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java:23-167`

**Interfaces:**
- Consumes: Task 1 nested records and `decideRobustness`
- Preserves: public `ExecutionPlan resolve(Signals signals)`
- Adds: package-scoped constructor `StrategyConflictResolver(RobustnessPolicy)`
- Adds: package-scoped overload `ExecutionPlan resolve(Signals, RobustnessSnapshot)`
- Produces direct `routing.robustness.*` TraceStore keys

- [ ] **Step 1: Write failing SHADOW, ENFORCE, and compatibility tests**

Append:

```java
@Test
void shadowProposesOverdriveButExecutesTheFixedExtremeZBaseline() {
    StrategyConflictResolver resolver = new StrategyConflictResolver(policy(
            StrategyConflictResolver.RobustnessMode.SHADOW,
            Map.of(
                    ExecutionPlan.PrimaryMode.EXTREMEZ,
                    profile(ExecutionPlan.PrimaryMode.EXTREMEZ, 0.20d, 0.30d, 0.30d, 0.40d, 30),
                    ExecutionPlan.PrimaryMode.OVERDRIVE,
                    profile(ExecutionPlan.PrimaryMode.OVERDRIVE, 0.70d, 0.80d, 0.75d, 0.65d, 30))));

    ExecutionPlan plan = resolver.resolve(
            new StrategyConflictResolver.Signals(true, true, true, false),
            completeSnapshot());

    assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
    assertEquals("OVERDRIVE", TraceStore.get("routing.robustness.proposedMode"));
    assertEquals("EXTREMEZ", TraceStore.get("routing.robustness.executedMode"));
    assertEquals("SHADOW", TraceStore.get("routing.robustness.mode"));
    assertEquals("shadow-proposal", TraceStore.get("routing.robustness.decisionReason"));
}

@Test
void enforceUsesTheEligibleProposalAndStillActivatesOnlyOneBooster() {
    StrategyConflictResolver resolver = new StrategyConflictResolver(policy(
            StrategyConflictResolver.RobustnessMode.ENFORCE,
            Map.of(
                    ExecutionPlan.PrimaryMode.EXTREMEZ,
                    profile(ExecutionPlan.PrimaryMode.EXTREMEZ, 0.20d, 0.30d, 0.30d, 0.40d, 30),
                    ExecutionPlan.PrimaryMode.OVERDRIVE,
                    profile(ExecutionPlan.PrimaryMode.OVERDRIVE, 0.70d, 0.80d, 0.75d, 0.65d, 30))));

    ExecutionPlan plan = resolver.resolve(
            new StrategyConflictResolver.Signals(true, true, true, false),
            completeSnapshot());

    assertEquals(ExecutionPlan.PrimaryMode.OVERDRIVE, plan.primaryMode());
    assertFalse(plan.extremeZEnabled());
    assertTrue(plan.overdriveEnabled());
    assertFalse(plan.hypernovaEnabled());
    assertEquals("OVERDRIVE", TraceStore.get("routing.robustness.executedMode"));
}

@Test
void publicResolveWithoutProfilePreservesFixedPriority() {
    ExecutionPlan plan = new StrategyConflictResolver().resolve(
            new StrategyConflictResolver.Signals(true, true, true, true));

    assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
    assertEquals("snapshot-incomplete", TraceStore.get("routing.robustness.decisionReason"));
}
```

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.orchestration.StrategyConflictResolverTest" --no-daemon --project-cache-dir $pcd
```

Expected: compilation fails because the package-scoped constructor and two-argument `resolve` method do not exist.

- [ ] **Step 3: Refactor fixed selection and attach the robustness decision**

Add the field and constructors:

```java
private final RobustnessPolicy robustnessPolicy;

public StrategyConflictResolver() {
    this(RobustnessPolicy.shadowWithoutProfile());
}

StrategyConflictResolver(RobustnessPolicy robustnessPolicy) {
    this.robustnessPolicy = robustnessPolicy == null
            ? RobustnessPolicy.shadowWithoutProfile()
            : robustnessPolicy;
}
```

Replace the public `resolve` body with delegation and add the package-scoped overload:

```java
public ExecutionPlan resolve(Signals signals) {
    return resolve(signals, RobustnessSnapshot.missing());
}

ExecutionPlan resolve(Signals signals, RobustnessSnapshot snapshot) {
    Signals safeSignals = signals == null
            ? new Signals(false, false, false, false)
            : signals;
    ExecutionPlan.PrimaryMode baselineMode = fixedMode(safeSignals);
    RobustnessDecision decision = decideRobustness(baselineMode, snapshot, robustnessPolicy);
    ExecutionPlan plan = buildPlan(safeSignals, decision.executedMode());
    trace(plan, decision);
    return plan;
}

private static ExecutionPlan.PrimaryMode fixedMode(Signals signals) {
    if (signals.lowRecall()) {
        return ExecutionPlan.PrimaryMode.EXTREMEZ;
    }
    if (signals.highRiskTail()) {
        return ExecutionPlan.PrimaryMode.HYPERNOVA;
    }
    if (signals.lowAuthority() || signals.contradiction()) {
        return ExecutionPlan.PrimaryMode.OVERDRIVE;
    }
    return ExecutionPlan.PrimaryMode.NORMAL;
}
```

Move the existing trigger, requested-mode, enabled-flag, suppression, knob, and `ExecutionPlan` construction into this exact helper. Do not change the existing knob names or stage order:

```java
private static ExecutionPlan buildPlan(Signals signals, ExecutionPlan.PrimaryMode mode) {
    List<String> triggers = new ArrayList<>(4);
    if (signals.lowRecall()) triggers.add("lowRecall");
    if (signals.lowAuthority()) triggers.add("lowAuthority");
    if (signals.contradiction()) triggers.add("contradiction");
    if (signals.highRiskTail()) triggers.add("highRiskTail");

    boolean requestedExtremeZ = signals.lowRecall();
    boolean requestedOverdrive = signals.lowAuthority() || signals.contradiction();
    boolean requestedHypernova = signals.highRiskTail();
    boolean extremeZ = mode == ExecutionPlan.PrimaryMode.EXTREMEZ;
    boolean overdrive = mode == ExecutionPlan.PrimaryMode.OVERDRIVE;
    boolean hypernova = mode == ExecutionPlan.PrimaryMode.HYPERNOVA;
    String suppressed = suppressedModes(
            mode, requestedExtremeZ, requestedOverdrive, requestedHypernova);

    Map<String, Object> knobs = new LinkedHashMap<>();
    knobs.put("extremeZ.aggressive", extremeZ);
    knobs.put("overdrive.aggressive", overdrive);
    knobs.put("gateChain.sharp", !triggers.isEmpty());
    knobs.put("breaker.failSoft", true);
    knobs.put("starvationLadder.deterministic", true);
    knobs.put("cancelShield.breadcrumb", !triggers.isEmpty());
    knobs.put("promptBuilder.required", true);
    knobs.put("specialMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
    knobs.put("specialMode.conflict.suppressed", suppressed);

    return new ExecutionPlan(
            mode, extremeZ, overdrive, hypernova,
            triggers, ExecutionPlan.DEFAULT_STAGES, knobs);
}
```

Change `trace(ExecutionPlan plan)` to `trace(ExecutionPlan plan, RobustnessDecision decision)`. Preserve every existing trace statement and append these direct keys inside the same `try` block:

```java
TraceStore.put("routing.robustness.mode", decision.mode().name());
TraceStore.put("routing.robustness.profileHash", safeToken(decision.profileHash()));
TraceStore.put("routing.robustness.candidateCount", decision.candidateCount());
TraceStore.put("routing.robustness.survivorCount", decision.survivorCount());
TraceStore.put("routing.robustness.proposedMode", decision.proposedMode().name());
TraceStore.put("routing.robustness.executedMode", decision.executedMode().name());
TraceStore.put("routing.robustness.baselineScore", round6(decision.baselineScore()));
TraceStore.put("routing.robustness.lowerTailScore", round6(decision.proposedScore()));
TraceStore.put("routing.robustness.promotionMargin",
        round6(Math.max(0.0d, decision.proposedScore() - decision.baselineScore())));
TraceStore.put("routing.robustness.decisionReason", safeToken(decision.reason()));
```

Keep these writes inside the existing trace `try/catch`, after the immutable
`ExecutionPlan` has been built. A trace write exception must leave the returned
plan unchanged and is classified as `trace-write-failed`; do not retry the
resolver or select another booster from the catch path.

Add:

```java
private static double round6(double value) {
    if (!Double.isFinite(value)) {
        return 0.0d;
    }
    return Math.round(value * 1_000_000.0d) / 1_000_000.0d;
}
```

- [ ] **Step 4: Run resolver tests to verify GREEN and compatibility**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.orchestration.StrategyConflictResolverTest" --no-daemon --project-cache-dir $pcd
```

Expected: all original fixed-priority assertions pass; SHADOW proposes but executes `EXTREMEZ`; injected ENFORCE selects only `OVERDRIVE`.

- [ ] **Step 5: Record or create the task commit**

Suggested commit: `feat: add shadow and enforce strategy decisions`

If commits are separately authorized:

```powershell
git add main/java/com/example/lms/orchestration/StrategyConflictResolver.java src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java
git commit -m "feat: add shadow and enforce strategy decisions"
```

---

### Task 3: Freeze request evidence in ExecutionPlanApplier

**Files:**
- Modify: `main/java/com/example/lms/orchestration/ExecutionPlanApplier.java:19-64`
- Modify: `main/java/com/example/lms/orchestration/ExecutionPlanApplier.java:130-148`
- Modify: `src/test/java/com/example/lms/orchestration/ExecutionPlanApplierTest.java:24-149`

**Interfaces:**
- Consumes: Task 2 `StrategyConflictResolver.resolve(Signals, RobustnessSnapshot)`
- Produces: one `RobustnessSnapshot` per `apply` invocation
- Uses existing budget facts: `timeBudget.forceFallback` and `zero100.timeBudget.forceFallback`
- Emits no raw trace value; malformed metrics use `routing.robustness.decisionReason=invalid-metric`

- [ ] **Step 1: Write failing snapshot and budget-fallback tests**

Append:

```java
@Test
void completeEvidenceAllowsShadowProposalWithoutChangingTheExecutedPlan() {
    TraceStore.put("outCount", 0L);
    TraceStore.put("overdrive.authority.avg", 0.20d);
    TraceStore.put("overdrive.contradiction.mean", 0.80d);
    TraceStore.put("timeBudget.forceFallback", false);
    GuardContext ctx = new GuardContext();
    StrategyConflictResolver resolver = new StrategyConflictResolver(policyForApplier(
            StrategyConflictResolver.RobustnessMode.SHADOW));

    ExecutionPlan plan = new ExecutionPlanApplier(resolver).apply(ctx, normalSignals(false));

    assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
    assertEquals("r1a1c1t0", TraceStore.get("routing.robustness.bucket"));
    assertEquals("OVERDRIVE", TraceStore.get("routing.robustness.proposedMode"));
    assertEquals("EXTREMEZ", TraceStore.get("routing.robustness.executedMode"));
    assertEquals(Boolean.TRUE, ctx.getPlanOverride("extremeZ.enabled"));
    assertEquals(Boolean.FALSE, ctx.getPlanOverride("overdrive.enabled"));
}

@Test
void budgetFallbackBlocksAnOtherwiseEligibleProposal() {
    TraceStore.put("outCount", 0L);
    TraceStore.put("overdrive.authority.avg", 0.20d);
    TraceStore.put("overdrive.contradiction.mean", 0.80d);
    TraceStore.put("timeBudget.forceFallback", true);
    GuardContext ctx = new GuardContext();
    StrategyConflictResolver resolver = new StrategyConflictResolver(policyForApplier(
            StrategyConflictResolver.RobustnessMode.ENFORCE));

    ExecutionPlan plan = new ExecutionPlanApplier(resolver).apply(ctx, normalSignals(false));

    assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
    assertEquals("budget-exceeded", TraceStore.get("routing.robustness.decisionReason"));
}

@Test
void missingBudgetEvidenceFailsClosed() {
    TraceStore.put("outCount", 0L);
    TraceStore.put("overdrive.authority.avg", 0.20d);
    TraceStore.put("overdrive.contradiction.mean", 0.80d);
    GuardContext ctx = new GuardContext();
    StrategyConflictResolver resolver = new StrategyConflictResolver(policyForApplier(
            StrategyConflictResolver.RobustnessMode.ENFORCE));

    ExecutionPlan plan = new ExecutionPlanApplier(resolver).apply(ctx, normalSignals(false));

    assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
    assertEquals("snapshot-incomplete", TraceStore.get("routing.robustness.decisionReason"));
}

private static StrategyConflictResolver.RobustnessPolicy policyForApplier(
        StrategyConflictResolver.RobustnessMode mode) {
    StrategyConflictResolver.CandidateProfile extremeZ =
            new StrategyConflictResolver.CandidateProfile(
                    ExecutionPlan.PrimaryMode.EXTREMEZ,
                    new StrategyConflictResolver.RobustnessVector(0.20d, 0.30d, 0.30d, 0.40d),
                    30, "profile-v1", false, true);
    StrategyConflictResolver.CandidateProfile overdrive =
            new StrategyConflictResolver.CandidateProfile(
                    ExecutionPlan.PrimaryMode.OVERDRIVE,
                    new StrategyConflictResolver.RobustnessVector(0.70d, 0.80d, 0.75d, 0.65d),
                    30, "profile-v1", false, true);
    return new StrategyConflictResolver.RobustnessPolicy(
            mode,
            "r1a1c1t0",
            "profile-v1",
            Map.of(extremeZ.mode(), extremeZ, overdrive.mode(), overdrive),
            30,
            0.25d,
            0.05d);
}
```

Add `import java.util.Map;`.

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.orchestration.ExecutionPlanApplierTest" --no-daemon --project-cache-dir $pcd
```

Expected: the SHADOW proposal test fails because `apply` still calls the one-argument resolver method and no robustness bucket is traced.

- [ ] **Step 3: Derive and pass exactly one immutable snapshot**

Change `apply` to:

```java
public ExecutionPlan apply(GuardContext ctx, OrchestrationSignals orchestrationSignals) {
    StrategyConflictResolver.Signals signals = deriveSignals(ctx, orchestrationSignals);
    StrategyConflictResolver.RobustnessSnapshot snapshot = deriveRobustnessSnapshot(signals);
    ExecutionPlan plan = resolver.resolve(signals, snapshot);
    DualGearPolicy.Decision gearDecision = dualGearPolicy.decide(signals);
    applyOverrides(ctx, plan, gearDecision);
    traceApplied(plan, gearDecision);
    return plan;
}
```

Add these private helpers after `deriveSignals`:

```java
private StrategyConflictResolver.RobustnessSnapshot deriveRobustnessSnapshot(
        StrategyConflictResolver.Signals signals) {
    TraceStore.put("routing.robustness.invalidMetric", false);
    boolean hasCoverage = hasTrace("outCount");
    boolean hasAuthority = hasTrace("overdrive.authority.avg") || hasTrace("authority.avg");
    boolean hasContradiction = hasTrace("overdrive.contradiction.mean")
            || hasTrace("extremez.risk.contradictionScore")
            || hasTrace("rag.contradiction.score");
    boolean hasBudget = hasTrace("timeBudget.forceFallback")
            || hasTrace("zero100.timeBudget.forceFallback");

    double coverage = hasCoverage
            ? clamp01(presentCount("outCount")
                    / (double) (LOW_RECALL_OUT_COUNT + 1L))
            : 0.0d;
    double authority = hasAuthority
            ? Math.min(
                    presentScore("overdrive.authority.avg", 1.0d),
                    presentScore("authority.avg", 1.0d))
            : 0.0d;
    double contradiction = hasContradiction
            ? Math.max(
                    presentScore("overdrive.contradiction.mean", 0.0d),
                    Math.max(
                            presentScore("extremez.risk.contradictionScore", 0.0d),
                            presentScore("rag.contradiction.score", 0.0d)))
            : 1.0d;
    boolean forceFallback = traceBool("timeBudget.forceFallback")
            || traceBool("zero100.timeBudget.forceFallback");
    boolean complete = hasCoverage && hasAuthority && hasContradiction && hasBudget;
    boolean validMetrics = !Boolean.TRUE.equals(
            TraceStore.get("routing.robustness.invalidMetric"));
    String bucket = "r" + bit(signals.lowRecall())
            + "a" + bit(signals.lowAuthority())
            + "c" + bit(signals.contradiction())
            + "t" + bit(signals.highRiskTail());

    TraceStore.put("routing.robustness.bucket", bucket);
    return new StrategyConflictResolver.RobustnessSnapshot(
            bucket,
            coverage,
            authority,
            1.0d - contradiction,
            signals.highRiskTail(),
            hasBudget && !forceFallback,
            complete,
            validMetrics);
}

private static long presentCount(String key) {
    Object raw = TraceStore.get(key);
    if (raw instanceof Number number
            && Double.isFinite(number.doubleValue())) {
        return Math.max(0L, number.longValue());
    }
    markInvalidMetric();
    return 0L;
}

private static double presentScore(String key, double absentFallback) {
    if (!hasTrace(key)) {
        return absentFallback;
    }
    Object raw = TraceStore.get(key);
    if (raw instanceof Number number && Double.isFinite(number.doubleValue())) {
        return clamp01(number.doubleValue());
    }
    markInvalidMetric();
    return 0.0d;
}

private static void markInvalidMetric() {
    TraceStore.put("routing.robustness.invalidMetric", true);
    TraceStore.put("routing.robustness.invalidMetricReason", "invalid_number");
}

private static double clamp01(double value) {
    if (!Double.isFinite(value)) {
        return 0.0d;
    }
    return Math.max(0.0d, Math.min(1.0d, value));
}

private static int bit(boolean value) {
    return value ? 1 : 0;
}
```

Do not log `raw` or convert it to a trace string. Existing `traceDouble` behavior and stable reason codes remain unchanged.

- [ ] **Step 4: Run applier and resolver tests to verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.orchestration.ExecutionPlanApplierTest" --tests "com.example.lms.orchestration.StrategyConflictResolverTest" --no-daemon --project-cache-dir $pcd
```

Expected: complete evidence produces a SHADOW proposal while executing `EXTREMEZ`; forced or missing budget evidence preserves `EXTREMEZ` with the exact fallback reason.

- [ ] **Step 5: Record or create the task commit**

Suggested commit: `feat: derive robust routing snapshots`

If commits are separately authorized:

```powershell
git add main/java/com/example/lms/orchestration/ExecutionPlanApplier.java src/test/java/com/example/lms/orchestration/ExecutionPlanApplierTest.java
git commit -m "feat: derive robust routing snapshots"
```

---

### Task 4: Prove exhaustive single-booster and fail-closed invariants

**Files:**
- Modify: `src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java:23-167`
- Modify: `src/test/java/com/example/lms/orchestration/ExecutionPlanApplierTest.java:98-149`
- Reuse unchanged: `src/test/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspectTest.java`
- Reuse unchanged: `src/test/java/ai/abandonware/nova/orch/aop/RagCompressionAspectTest.java`

**Interfaces:**
- Consumes: Tasks 1-3 behavior
- Produces: exhaustive proof for all boolean trigger masks and malformed metric cases

- [ ] **Step 1: Add the exhaustive single-booster test**

Append to `StrategyConflictResolverTest`:

```java
@Test
void allSixteenSignalCombinationsKeepAtMostOnePrimaryBooster() {
    StrategyConflictResolver resolver = new StrategyConflictResolver();

    for (int mask = 0; mask < 16; mask++) {
        TraceStore.clear();
        StrategyConflictResolver.Signals signals = new StrategyConflictResolver.Signals(
                (mask & 1) != 0,
                (mask & 2) != 0,
                (mask & 4) != 0,
                (mask & 8) != 0);

        ExecutionPlan plan = resolver.resolve(signals);
        long enabledCount = List.of(
                        plan.extremeZEnabled(),
                        plan.overdriveEnabled(),
                        plan.hypernovaEnabled())
                .stream()
                .filter(Boolean::booleanValue)
                .count();

        assertTrue(enabledCount <= 1L, "mask=" + mask);
        assertEquals(
                plan.primaryMode() == ExecutionPlan.PrimaryMode.NORMAL ? 0L : 1L,
                enabledCount,
                "mask=" + mask);
    }
}
```

- [ ] **Step 2: Add malformed profile and no-cascade assertions**

Append to `StrategyConflictResolverTest`:

```java
@Test
void staleAndUndersampledProfilesFailBackWithoutEnforcement() {
    StrategyConflictResolver.CandidateProfile stale = new StrategyConflictResolver.CandidateProfile(
            ExecutionPlan.PrimaryMode.EXTREMEZ,
            new StrategyConflictResolver.RobustnessVector(1.0d, 1.0d, 1.0d, 1.0d),
            30,
            "profile-v1",
            true,
            true);
    StrategyConflictResolver.RobustnessDecision staleDecision =
            StrategyConflictResolver.decideRobustness(
                    ExecutionPlan.PrimaryMode.EXTREMEZ,
                    completeSnapshot(),
                    policy(StrategyConflictResolver.RobustnessMode.ENFORCE,
                            Map.of(stale.mode(), stale)));
    assertEquals("profile-stale", staleDecision.reason());
    assertFalse(staleDecision.enforced());

    StrategyConflictResolver.CandidateProfile undersampled =
            new StrategyConflictResolver.CandidateProfile(
                    ExecutionPlan.PrimaryMode.EXTREMEZ,
                    new StrategyConflictResolver.RobustnessVector(1.0d, 1.0d, 1.0d, 1.0d),
                    29,
                    "profile-v1",
                    false,
                    true);
    StrategyConflictResolver.RobustnessDecision sampleDecision =
            StrategyConflictResolver.decideRobustness(
                    ExecutionPlan.PrimaryMode.EXTREMEZ,
                    completeSnapshot(),
                    policy(StrategyConflictResolver.RobustnessMode.ENFORCE,
                            Map.of(undersampled.mode(), undersampled)));
    assertEquals("insufficient-samples", sampleDecision.reason());
    assertFalse(sampleDecision.enforced());
}
```

Append to `ExecutionPlanApplierTest`:

```java
@Test
void malformedRobustnessMetricNeverAppearsInReasonTraces() {
    String raw = "private query ownerToken=fake-token";
    TraceStore.put("outCount", 0L);
    TraceStore.put("overdrive.authority.avg", raw);
    TraceStore.put("overdrive.contradiction.mean", 0.80d);
    TraceStore.put("timeBudget.forceFallback", false);
    GuardContext ctx = new GuardContext();

    new ExecutionPlanApplier(new StrategyConflictResolver()).apply(ctx, normalSignals(false));

    assertEquals("invalid_number", TraceStore.get("routing.robustness.invalidMetricReason"));
    assertEquals("invalid-metric", TraceStore.get("routing.robustness.decisionReason"));
    assertEquals("EXTREMEZ", TraceStore.get("routing.robustness.executedMode"));
    assertFalse(String.valueOf(TraceStore.get("routing.robustness.invalidMetricReason")).contains(raw));
}
```

- [ ] **Step 3: Run both focused test classes**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.orchestration.StrategyConflictResolverTest" --tests "com.example.lms.orchestration.ExecutionPlanApplierTest" --no-daemon --project-cache-dir $pcd
```

Expected: all tests pass. If a failure exposes more than one enabled booster, a raw value in diagnostics, or enforcement on invalid evidence, classify the failed invariant and return to the exact Task 1, 2, or 3 code step that owns it; do not expand the target-file set.

- [ ] **Step 4: Run the existing CVaR regression test**

Run:

```powershell
.\gradlew.bat test --tests "com.nova.protocol.fusion.NovaCvarAggregatorTest" --no-daemon --project-cache-dir $pcd
```

Expected: all five existing tests pass, including `lowerTailMeanClampsInvalidScoresForRiskDiagnostics`; `CvarAggregator.java` remains byte-for-byte unchanged.

- [ ] **Step 5: Prove booster failure cannot cascade to a second booster**

Run the existing fail-soft and primary-mode suppression tests without modifying either aspect:

```powershell
.\gradlew.bat test --tests "ai.abandonware.nova.orch.aop.ExtremeZBurstAspectTest.planOverrideCanEnableButMissingRetrieverFailsSoft" --tests "ai.abandonware.nova.orch.aop.ExtremeZBurstAspectTest.executionPlanPrimaryOverdriveSuppressesExtremeZEvenWhenFlagRemainsTrue" --tests "ai.abandonware.nova.orch.aop.RagCompressionAspectTest.executionPlanPrimaryExtremeZSuppressesAutoOverdriveCompression" --no-daemon --project-cache-dir $pcd
```

Expected: a missing ExtremeZ dependency returns the regular RAG result, and an already-selected primary mode suppresses the other booster path. Classify any changed behavior as `booster-failed`; do not select or invoke a second high-power booster in the same request and do not add a new cascade mechanism.

- [ ] **Step 6: Record or create the task commit**

Suggested commit: `test: lock robust routing invariants`

If commits are separately authorized:

```powershell
git add src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java src/test/java/com/example/lms/orchestration/ExecutionPlanApplierTest.java
git commit -m "test: lock robust routing invariants"
```

---

### Task 5: Run Desktop verification and prepare the evidence handoff

**Files:**
- Verify unchanged: `main/java/com/nova/protocol/fusion/CvarAggregator.java`
- Verify unchanged: `main/java/com/example/lms/prompt/PromptBuilder.java`
- Verify: `main/java/com/example/lms/orchestration/StrategyConflictResolver.java`
- Verify: `main/java/com/example/lms/orchestration/ExecutionPlanApplier.java`
- Verify: both focused test files

**Interfaces:**
- Consumes: all prior task outputs
- Produces: Desktop command evidence, source hashes, failure classification, and an explicit runtime-lineage `HOLD`

- [ ] **Step 1: Run Desktop collision and ownership preflight**

From `C:\AbandonWare\demo-1\demo-1\src`:

```powershell
git worktree list
git branch --show-current
git status --short
if (Test-Path ".git\index.lock") { throw "index-lock-conflict" }
Get-NetTCPConnection -LocalPort 8080,8081 -ErrorAction SilentlyContinue
if (Test-Path "__patch_drop__\janitor_inventory.ps1") {
    & "__patch_drop__\janitor_inventory.ps1"
}
```

Expected: the executor can identify the Desktop branch and worktree, no target-file dirty overlap or index lock exists, and no ambiguous top-level PatchDrop blocks the source owner. Otherwise stop with the exact observed failure classification.

- [ ] **Step 2: Configure isolated Desktop caches**

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
```

Expected: both cache paths exist outside the shared source tree.

- [ ] **Step 3: Run source and dependency hard gates**

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

Expected: exit code `0`; LangChain4j remains `1.0.1`; active source roots remain unchanged.

- [ ] **Step 4: Run focused RED/GREEN suites and cross-subsystem contracts**

```powershell
.\gradlew.bat test --tests "com.example.lms.orchestration.StrategyConflictResolverTest" --tests "com.example.lms.orchestration.ExecutionPlanApplierTest" --tests "com.nova.protocol.fusion.NovaCvarAggregatorTest" --no-daemon --project-cache-dir $pcd
.\gradlew.bat crossSubsystemContractTest --no-daemon --project-cache-dir $pcd
```

Expected: both commands exit `0`; no single-booster, PromptBuilder, CVaR, cancellation, or cross-subsystem regression is reported.

- [ ] **Step 5: Compile the active modules**

```powershell
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat :app:classes --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar --no-daemon --project-cache-dir $pcd
```

Expected: all commands exit `0`. This proves build compatibility, not provider/runtime lineage.

- [ ] **Step 6: Run count-only secret and undeclared-write checks**

```powershell
$changed = @(
  "main/java/com/example/lms/orchestration/StrategyConflictResolver.java",
  "main/java/com/example/lms/orchestration/ExecutionPlanApplier.java",
  "src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java",
  "src/test/java/com/example/lms/orchestration/ExecutionPlanApplierTest.java"
)
$secretPatterns = @(
  'sk-[A-Za-z0-9_-]{20,}',
  'AKIA[0-9A-Z]{16}',
  '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----',
  '(?i)Authorization\s*:\s*Bearer\s+[A-Za-z0-9._-]{16,}'
)
$secretCount = 0
foreach ($path in $changed) {
    $text = Get-Content -LiteralPath $path -Raw
    foreach ($pattern in $secretPatterns) {
        $secretCount += ([regex]::Matches($text, $pattern)).Count
    }
}
[pscustomobject]@{ files = $changed.Count; secretMatchCount = $secretCount }
Get-FileHash -Algorithm SHA256 $changed
```

Expected: `files=4`, `secretMatchCount=0`, and hashes are recorded. Verify `CvarAggregator.java` and `PromptBuilder.java` still match their preflight hashes.

- [ ] **Step 7: Record final evidence without overstating runtime success**

Record:

```text
implementationVerdict=APPLY only if every source/test/build gate passed
robustnessRuntimeMode=SHADOW
profileTransport=evidence_needed
providerAttemptResponseLineage=evidence_needed
runtimeLineageVerdict=HOLD
desktopFinalProof=<attach exact Desktop command output and hashes>
```

Expected: the handoff distinguishes source/build proof from unavailable provider/runtime lineage.

- [ ] **Step 8: Record or create the final integration commit**

Suggested commit: `feat: add shadow robust rag routing`

If commits are separately authorized and prior task commits were not created:

```powershell
git add main/java/com/example/lms/orchestration/StrategyConflictResolver.java main/java/com/example/lms/orchestration/ExecutionPlanApplier.java src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java src/test/java/com/example/lms/orchestration/ExecutionPlanApplierTest.java
git commit -m "feat: add shadow robust rag routing"
```

If prior task commits were created, do not create an empty integration commit.

## Follow-up Boundary

Do not connect a profile store or activate production `ENFORCE` under this plan.
After Phase 1 produces Desktop evidence, create a separate evidence-backed spec
for the proven profile loader/configuration owner, fixture-generation command,
profile freshness policy, and promotion operator.
