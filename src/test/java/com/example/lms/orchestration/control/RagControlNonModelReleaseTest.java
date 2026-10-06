package com.example.lms.orchestration.control;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline adapter/composer/presentation contract; no provider, database, or browser calls. */
class RagControlNonModelReleaseTest {

    private static final String TIMELINE = "synthetic-non-model-release-request-a";
    private static final String EXCERPT_REASON = "verification_unavailable_excerpt";
    private static final String GUIDANCE_REASON = "verification_unavailable_guidance";
    private static final String EXCERPT =
            "Verification unavailable. Source excerpt: fixture alpha lists item beta. [S1]";
    private static final String GUIDANCE =
            "Verification unavailable. General guidance: inspect the fixture connection first.";

    @BeforeEach
    void beginSyntheticRequest() {
        TraceStore.clear();
        setTimeline(TIMELINE);
    }

    @AfterEach
    void clearSyntheticRequest() {
        TraceStore.clear();
    }

    @Test
    void trustedExcerptSurvivesEnforceWithoutProviderLineage() {
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(false), EXCERPT_REASON, EXCERPT);

        assertUnverified(input);
        assertReleased(project(input, EXCERPT), EXCERPT);
    }

    @Test
    void trustedGuidanceSurvivesEnforceWithoutProviderLineage() {
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(false), GUIDANCE_REASON, GUIDANCE);

        assertUnverified(input);
        assertReleased(project(input, GUIDANCE), GUIDANCE);
    }

    @Test
    void realTrackerFinalRowBindsNonModelBodyWithoutInventingModelLineage() {
        var tracker=new ModelRuntimeHealthTracker();
        String timeline=tracker.beginRequestTimeline("synthetic-request","synthetic-session");
        setTimeline(timeline);
        var input=authorize(unknownInput(false),EXCERPT_REASON,EXCERPT);
        tracker.recordRequestPhase(timeline,"dispatch",null,null,null);
        tracker.recordRequestPhase(timeline,"pending",null,null,null);
        tracker.recordRequestPhase(timeline,"final_boundary",com.example.lms.trace.SafeRedactor.hashValue(EXCERPT),null,null);
        var rows=tracker.redactedRequestTimeline(timeline);
        assertFalse(rows.isEmpty());
        var findings=new RagControlRuntimeAdapter().collect(input,tracker);
        var plan=new RagGuardProbeComposer().compose(findings);
        assertEquals(RagActionPlan.Action.DEGRADE,plan.action());
        assertFalse(plan.lineageComplete());
        assertFalse(input.verificationKnown());
        assertEquals(EXCERPT_REASON,plan.reasonCode());
    }

    @Test
    void bodyChangedAfterTrustedDecisionIsHeld() {
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(false), EXCERPT_REASON, EXCERPT);
        String changed = EXCERPT + " UNAPPROVED_BODY_SENTINEL";

        assertHeld(project(input, changed), "UNAPPROVED_BODY_SENTINEL");
    }

    @Test
    void ordinaryUnknownVerificationCannotReusePartialReleaseException() {
        String rawDraft = "ORDINARY_UNKNOWN_DRAFT_SENTINEL";

        assertHeld(project(unknownInput(false), rawDraft), rawDraft);
    }

    @Test
    void trustedPartialDecisionCannotOverrideExistingHardGuard() {
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(true), EXCERPT_REASON, EXCERPT);

        RagControlPresentationBoundary.Projection projection = project(input, EXCERPT);

        assertHeld(projection, EXCERPT);
        assertTrue(projection.plan().hardGuardLocked());
    }

    @Test
    void trustedDecisionFromAnotherTimelineCannotReleaseCurrentBody() {
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(false), EXCERPT_REASON, EXCERPT);
        setTimeline("synthetic-non-model-release-request-b");

        assertHeld(project(input, EXCERPT), EXCERPT);
    }

    @Test
    void missingRequestBindingCannotAuthorizePartialRelease() {
        TraceStore.clear();
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(false), GUIDANCE_REASON, GUIDANCE);

        assertHeld(project(input, GUIDANCE), GUIDANCE);
    }

    @Test
    void unknownReasonCannotAuthorizeOrdinaryDraft() {
        String draft = "UNKNOWN_RELEASE_REASON_DRAFT_SENTINEL";
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(false), "verification_rejected", draft);

        assertHeld(project(input, draft), draft);
    }

    @Test
    void suppliedFinalHashMismatchCannotBeTreatedAsAbsentLineage() {
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(false), EXCERPT_REASON, EXCERPT);
        List<Map<String, Object>> timeline = List.of(Map.of(
                "requestHash", "hash:111111111111",
                "phase", "final_boundary",
                "finalHash", "hash:ffffffffffff"));

        assertDirectHold(input, timeline, List.of());
    }

    @Test
    void suppliedForeignTimelineRowCannotBeTreatedAsAbsentLineage() {
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(false), EXCERPT_REASON, EXCERPT);
        List<Map<String, Object>> timeline = List.of(Map.of(
                "requestHash", "hash:111111111111",
                "phase", "dispatch",
                "timelineId", "synthetic-foreign-request"));

        assertDirectHold(input, timeline, List.of());
    }

    @Test
    void matchingForeignHashesWithoutTimelineIdentityCannotAuthorizeRelease() {
        var input = authorize(unknownInput(false), EXCERPT_REASON, EXCERPT);
        assertDirectHold(input, List.of(Map.of("requestHash", "hash:111111111111", "phase", "dispatch")),
                List.of(Map.of("requestHash", "hash:111111111111", "logicalCallOrdinal", 1,
                        "attemptOrdinal", 1, "attemptTotal", 1, "attemptDropped", 0)));
    }

    @Test
    void wireAttemptWithoutProviderReceiptCannotAuthorizeRelease() {
        var input = authorize(unknownInput(false), EXCERPT_REASON, EXCERPT);
        assertDirectHold(input, List.of(), List.of(Map.of("timelineId", TIMELINE,
                "requestHash", "hash:111111111111", "logicalCallOrdinal", 1,
                "attemptOrdinal", 1, "attemptTotal", 1, "attemptDropped", 0,
                "wireAttemptObserved", true, "providerReceiptObserved", false)));
    }

    @Test
    void cancelledThreadCannotProjectTrustedExcerpt() {
        var input = authorize(unknownInput(false), EXCERPT_REASON, EXCERPT);
        Thread.currentThread().interrupt();
        try {
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,
                    () -> project(input, EXCERPT));
        } finally { Thread.interrupted(); }
    }

    @Test
    void duplicateOrDroppedAttemptCannotBeTreatedAsAbsentLineage() {
        RagControlRuntimeAdapter.RuntimeInput input =
                authorize(unknownInput(false), EXCERPT_REASON, EXCERPT);
        Map<String, Object> duplicate = Map.of(
                "requestHash", "hash:111111111111",
                "logicalCallOrdinal", 1, "attemptOrdinal", 1,
                "attemptTotal", 2, "attemptDropped", 0);
        Map<String, Object> dropped = Map.of(
                "requestHash", "hash:111111111111",
                "logicalCallOrdinal", 1, "attemptOrdinal", 1,
                "attemptTotal", 1, "attemptDropped", 1);

        assertDirectHold(input, List.of(), List.of(duplicate, duplicate));
        assertDirectHold(input, List.of(), List.of(dropped));
    }

    @Test
    void probeWithMatchingReasonCannotAuthorizeOrdinaryUnknown() {
        List<RagControlFinding> findings = new ArrayList<>(new RagControlRuntimeAdapter().collect(
                unknownInput(false), TIMELINE, List.of(), List.of()));
        findings.add(new RagControlFinding(
                "synthetic-arbitrary-probe",
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.FailureClass.OBSERVABILITY_GAP,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.PROBE,
                RagActionPlan.Action.DEGRADE,
                EXCERPT_REASON,
                "hash:111111111111",
                RagControlFinding.LineageStatus.MISSING,
                Map.of("nonModelReleaseKind", "SUPPORTED_EXCERPT",
                        "nonModelReleaseBodyHash", "hash:222222222222",
                        "nonModelReleaseRequestHash", "hash:333333333333",
                        "nonModelReleaseBound", true)));

        RagActionPlan plan = new RagGuardProbeComposer().compose(findings);

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertFalse(plan.lineageComplete());
    }

    private static void assertDirectHold(
            RagControlRuntimeAdapter.RuntimeInput input,
            List<Map<String, Object>> timeline,
            List<Map<String, Object>> attempts) {
        RagActionPlan plan = new RagGuardProbeComposer().compose(
                new RagControlRuntimeAdapter().collect(input, TIMELINE, timeline, attempts));
        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertFalse(plan.lineageComplete());
    }

    private static RagControlRuntimeAdapter.RuntimeInput unknownInput(boolean hardGuardHeld) {
        // Keep verification required and unaccepted. A release exception must not fake PASS.
        return new RagControlRuntimeAdapter.RuntimeInput(
                true, 1, 1, false, false, false, hardGuardHeld, true, true);
    }

    private static void setTimeline(String timeline) {
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timeline);
    }

    private static void assertUnverified(RagControlRuntimeAdapter.RuntimeInput input) {
        assertFalse(input.verificationKnown());
        assertFalse(input.verificationAccepted());
        assertTrue(input.verificationRequired());
    }

    /**
     * The old source compiles this test too: absence of the new method leaves its real
     * unknown-verification input intact, so the two positive cases fail on actual HOLD.
     * Invocation failures are never swallowed as a substitute for the expected result.
     */
    private static RagControlRuntimeAdapter.RuntimeInput authorize(
            RagControlRuntimeAdapter.RuntimeInput input, String reason, String body) {
        try {
            Method method = input.getClass().getMethod(
                    "withNonModelRelease", String.class, String.class);
            return (RagControlRuntimeAdapter.RuntimeInput) method.invoke(input, reason, body);
        } catch (NoSuchMethodException oldSource) {
            return input;
        } catch (InvocationTargetException failure) {
            throw new AssertionError("Non-model release authorization failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Non-model release contract is inaccessible", failure);
        }
    }

    private static RagControlPresentationBoundary.Projection project(
            RagControlRuntimeAdapter.RuntimeInput input, String body) {
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        assertEquals(RagControlRolloutState.Mode.ENFORCE, rollout.mode());
        RagControlRuntimeAdapter.capturePresentationInput(input);
        RagControlPresentationBoundary boundary = new RagControlPresentationBoundary(
                new RagControlCoordinator(new RagGuardProbeComposer(), rollout),
                new RagControlRuntimeAdapter(),
                new RagControlProjectionRenderer(),
                (ModelRuntimeHealthTracker) null); // No provider timeline rows or attempt receipts are available.
        return boundary.projectResult(body, true);
    }

    private static void assertReleased(
            RagControlPresentationBoundary.Projection projection, String expectedBody) {
        assertNotNull(projection.plan());
        assertEquals(RagControlRolloutState.Mode.ENFORCE, projection.plan().rolloutMode());
        assertTrue(projection.plan().enforced());
        assertFalse(projection.held());
        assertFalse(projection.plan().shouldStop());
        assertEquals(RagActionPlan.Action.DEGRADE, projection.plan().action());
        assertEquals(expectedBody, projection.visibleAnswer());
        assertEquals(expectedBody, projection.persistableAnswer());
        assertFalse(projection.visibleAnswer().contains(RagControlProjectionRenderer.TABLE_MARKER));
        // This projection's persistable body is chat history, not durable knowledge approval.
    }

    private static void assertHeld(
            RagControlPresentationBoundary.Projection projection, String forbiddenBody) {
        assertNotNull(projection.plan());
        assertEquals(RagControlRolloutState.Mode.ENFORCE, projection.plan().rolloutMode());
        assertTrue(projection.plan().enforced());
        assertTrue(projection.held());
        assertTrue(projection.plan().shouldStop());
        assertEquals(RagActionPlan.Action.HOLD, projection.plan().action());
        assertFalse(projection.visibleAnswer().contains(forbiddenBody));
        assertFalse(projection.persistableAnswer().contains(forbiddenBody));
    }
}
