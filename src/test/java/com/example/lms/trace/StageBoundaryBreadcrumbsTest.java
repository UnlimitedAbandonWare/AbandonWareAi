package com.example.lms.trace;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageBoundaryBreadcrumbsTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void recordsSearchAndPromptBoundaryBreadcrumbsWithoutRawSecrets() {
        String rawQuery = "raw customer query ownerToken=private-token";
        TraceStore.put("query", rawQuery);
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.naver.disabledReason", "missing_naver_key Authorization=private-token");
        TraceStore.put("webSearch.returnedCount", 3);
        TraceStore.put("webSearch.afterFilterCount", 0);
        TraceStore.put("finalContextCount", 0);

        List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> rows =
                StageBoundaryBreadcrumbs.recordFromCurrentTrace("pre_llm");

        assertEquals(2, rows.size());
        assertEquals("search", rows.get(0).stage());
        assertEquals("provider-disabled", rows.get(0).failureClass());
        assertEquals("prompt", rows.get(1).stage());
        assertEquals("context-missing", rows.get(1).failureClass());

        Map<?, ?> searchStep = assertInstanceOf(Map.class, TraceStore.get("mla.breadcrumb.step.search"));
        assertEquals("search", searchStep.get("stage"));
        assertEquals("provider-disabled", searchStep.get("failureClass"));
        assertEquals(Boolean.TRUE, searchStep.get("queryRedacted"));
        assertEquals(SafeRedactor.hash12(rawQuery), searchStep.get("queryHash12"));
        assertEquals(rawQuery.length(), searchStep.get("queryLength"));

        Map<?, ?> promptStep = assertInstanceOf(Map.class, TraceStore.get("mla.breadcrumb.step.prompt"));
        assertEquals("context-missing", promptStep.get("failureClass"));
        assertEquals(0, promptStep.get("contextCount"));

        List<?> breadcrumbs = assertInstanceOf(List.class, TraceStore.get("ml.breadcrumbs.v1"));
        assertEquals(2, breadcrumbs.size());
        Map<?, ?> row = assertInstanceOf(Map.class, breadcrumbs.get(0));
        assertEquals("StageBoundaryBreadcrumbs", row.get("component"));
        assertEquals("stage_boundary_observed", row.get("decision"));
        assertInstanceOf(Map.class, row.get("data"));

        String dump = rows + "\n" + TraceStore.getAll();
        assertFalse(dump.contains(rawQuery));
        assertFalse(dump.contains("private-token"));
        assertFalse(dump.contains("Authorization"));
    }

    @Test
    void unchangedBoundaryAcrossSnapshotsUpdatesLatestStepWithoutDuplicatingHistory() {
        String rawQuery = "same request query";
        TraceStore.put("query", rawQuery);
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.naver.disabledReasonCanonical", "missing_key");

        StageBoundaryBreadcrumbs.recordFromCurrentTrace("pre_llm");
        StageBoundaryBreadcrumbs.recordFromCurrentTrace("final");

        List<?> breadcrumbs = assertInstanceOf(List.class, TraceStore.get("ml.breadcrumbs.v1"));
        assertEquals(1, breadcrumbs.size(),
                "the final snapshot must not append the same stage observation twice");
        Map<?, ?> latest = assertInstanceOf(Map.class, TraceStore.get("mla.breadcrumb.step.search"));
        assertEquals("final", latest.get("phase"),
                "the per-stage projection should still expose the latest observation phase");
        assertEquals(SafeRedactor.hash12(rawQuery), latest.get("queryHash12"));
        assertEquals(rawQuery.length(), latest.get("queryLength"));

        TraceStore.put("web.naver.disabledReasonCanonical", "auth_missing");
        StageBoundaryBreadcrumbs.recordFromCurrentTrace("final_retry");

        assertEquals(2, breadcrumbs.size(),
                "a changed reason must remain a distinct boundary observation");
    }

    @Test
    void emitsRedactedTraceJsonBoundaryBreadcrumb() {
        Logger logger = (Logger) LoggerFactory.getLogger("TRACE_JSON");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        boolean previousEnabled = TraceLogger.enabled;
        double previousSample = TraceLogger.sample;
        try {
            TraceLogger.enabled = true;
            TraceLogger.sample = 1.0d;
            TraceStore.put("llm.client.failed", true);
            TraceStore.put("llm.client.errorType", "WebClientResponseException");
            TraceStore.put("llm.client.rawPrompt", "raw prompt private-token-should-not-surface");

            List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> rows =
                    StageBoundaryBreadcrumbs.recordFromCurrentTrace("final");

            assertEquals(1, rows.size());
            assertEquals("llm", rows.get(0).stage());
            assertEquals("catch", rows.get(0).failureClass());

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(rendered.contains("\"type\":\"stage_boundary_breadcrumb\""), rendered);
            assertTrue(rendered.contains("\"stage\":\"llm\""), rendered);
            assertTrue(rendered.contains("\"failureClass\":\"catch\""), rendered);
            assertFalse(rendered.contains("raw prompt"), rendered);
            assertFalse(rendered.contains("private-token"), rendered);
        } finally {
            logger.detachAppender(appender);
            TraceLogger.enabled = previousEnabled;
            TraceLogger.sample = previousSample;
        }
    }

    @Test
    void invalidCountValuesLeaveRedactedSuppressionBreadcrumb() {
        TraceStore.put("webSearch.returnedCount", "not-a-number");
        TraceStore.put("webSearch.afterFilterCount", 0);

        List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> rows =
                StageBoundaryBreadcrumbs.recordFromCurrentTrace("first_refine");

        assertEquals(1, rows.size());
        assertEquals("search", rows.get(0).stage());
        assertEquals("zero-result", rows.get(0).failureClass());
        assertEquals("number_parse", TraceStore.get("stageBoundary.suppressed.stage"));
        assertEquals("NumberFormatException", TraceStore.get("stageBoundary.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("stageBoundary.suppressed.number_parse"));
    }

    @Test
    void traceStoreWriteFailureEmitsLastResortStructuredLog() {
        Logger logger = (Logger) LoggerFactory.getLogger("TRACE_JSON");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        boolean previousEnabled = TraceLogger.enabled;
        double previousSample = TraceLogger.sample;
        try {
            TraceLogger.enabled = true;
            TraceLogger.sample = 1.0d;
            TraceStore.installContext(new ThrowingTraceMap(Map.of("llm.client.failed", true)));

            List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> rows =
                    StageBoundaryBreadcrumbs.recordFromCurrentTrace("second_refine");

            assertTrue(rows.isEmpty());
            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(rendered.contains("\"type\":\"stage_boundary_suppressed_write_failed\""), rendered);
            assertTrue(rendered.contains("\"stage\":\"recordFromCurrentTrace\""), rendered);
            assertTrue(rendered.contains("\"traceStoreWriteFailed\":true"), rendered);
            assertFalse(rendered.contains("ownerToken"), rendered);
            assertFalse(rendered.contains("private-token"), rendered);
        } finally {
            logger.detachAppender(appender);
            TraceLogger.enabled = previousEnabled;
            TraceLogger.sample = previousSample;
            TraceStore.clear();
        }
    }

    @Test
    void recordsFactStatusJudgeCallFailureAsUnknownVerificationOutcome() {
        TraceStore.put("factStatusClassifier.judge.path", "judgeChatModel");
        TraceStore.put("factStatusClassifier.judge.disabledReason", "judge_call_failed");

        List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> rows =
                StageBoundaryBreadcrumbs.recordFromCurrentTrace("final");

        assertEquals(1, rows.size());
        assertEquals("verification", rows.get(0).stage());
        assertEquals("catch", rows.get(0).failureClass());
        Map<?, ?> data = rows.get(0).data();
        assertEquals("fail_soft", data.get("status"));
        assertEquals("judge_call_failed", data.get("reasonCode"));
        assertEquals("fact_status_classifier", data.get("judgeLane"));
        assertEquals(1, data.get("judgeFailSoftLaneCount"));
        assertFalse(data.containsKey("judgeFailureCount"));
        assertEquals(Boolean.TRUE, data.get("judgeCallAttempted"));
        assertEquals(Boolean.FALSE, data.get("verificationOutcomeKnown"));
        assertEquals(Boolean.TRUE, data.get("redacted"));

        Map<?, ?> step = assertInstanceOf(Map.class, TraceStore.get("mla.breadcrumb.step.verification"));
        assertEquals(data, step);
        List<?> breadcrumbs = assertInstanceOf(List.class, TraceStore.get("ml.breadcrumbs.v1"));
        assertEquals(1, breadcrumbs.size());
    }

    @Test
    void recordsUnavailableClaimJudgeWithoutClaimingCallAttempt() {
        TraceStore.put("claimVerifier.judge.path", "judgeChatModel");
        TraceStore.put("claimVerifier.judge.disabledReason", "judge_model_unavailable");

        List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> rows =
                StageBoundaryBreadcrumbs.recordFromCurrentTrace("final");

        assertEquals(1, rows.size());
        Map<?, ?> data = rows.get(0).data();
        assertEquals("provider-disabled", data.get("failureClass"));
        assertEquals("judge_model_unavailable", data.get("reasonCode"));
        assertEquals("claim_verifier", data.get("judgeLane"));
        assertEquals(1, data.get("judgeFailSoftLaneCount"));
        assertEquals(Boolean.FALSE, data.get("judgeCallAttempted"));
        assertEquals(Boolean.FALSE, data.get("verificationOutcomeKnown"));
    }

    @Test
    void mixedJudgeFailSoftUsesCallFailurePrecedenceIndependentOfMapOrder() {
        Map<String, Object> claimFirst = new LinkedHashMap<>();
        claimFirst.put("claimVerifier.judge.disabledReason", "judge_model_unavailable");
        claimFirst.put("factStatusClassifier.judge.disabledReason", "judge_call_failed");
        Map<String, Object> factFirst = new LinkedHashMap<>();
        factFirst.put("factStatusClassifier.judge.disabledReason", "judge_call_failed");
        factFirst.put("claimVerifier.judge.disabledReason", "judge_model_unavailable");

        List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> firstRows =
                StageBoundaryBreadcrumbs.record("final", claimFirst);
        assertEquals(1, firstRows.size());
        Map<String, Object> first = firstRows.get(0).data();
        TraceStore.clear();
        List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> secondRows =
                StageBoundaryBreadcrumbs.record("final", factFirst);
        assertEquals(1, secondRows.size());
        Map<String, Object> second = secondRows.get(0).data();

        for (String key : List.of("failureClass", "reasonCode", "judgeLane", "judgeFailSoftLaneCount",
                "judgeCallAttempted", "verificationOutcomeKnown")) {
            assertEquals(first.get(key), second.get(key), key);
        }
        assertEquals("catch", first.get("failureClass"));
        assertEquals("judge_call_failed", first.get("reasonCode"));
        assertEquals("both", first.get("judgeLane"));
        assertEquals(2, first.get("judgeFailSoftLaneCount"));
        assertEquals(Boolean.TRUE, first.get("judgeCallAttempted"));
    }

    @Test
    void doesNotCreateVerificationBreadcrumbFromPathOnlyOrAbsentKeys() {
        assertTrue(StageBoundaryBreadcrumbs.record("final", Map.of()).isEmpty());
        assertTrue(StageBoundaryBreadcrumbs.record("final", Map.of(
                "factStatusClassifier.judge.path", "judgeChatModel",
                "claimVerifier.judge.path", "judgeChatModel")).isEmpty());
        assertEquals(null, TraceStore.get("mla.breadcrumb.step.verification"));
    }

    @Test
    void redactsUnknownJudgeReasonWithoutExposingRawValue() {
        String rawReason = "ownerToken=private-token Authorization=Bearer-private";
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("claimVerifier.judge.disabledReason", rawReason);

        List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> rows =
                StageBoundaryBreadcrumbs.record("final", meta);

        assertEquals(1, rows.size());
        Map<?, ?> data = rows.get(0).data();
        assertEquals("fallback", data.get("failureClass"));
        assertEquals("judge_fail_soft", data.get("reasonCode"));
        assertFalse(data.containsKey("judgeCallAttempted"),
                "an unknown safe fallback reason must not invent whether a judge call happened");
        String dump = rows + "\n" + TraceStore.getAll();
        assertFalse(dump.contains(rawReason));
        assertFalse(dump.contains("private-token"));
        assertFalse(dump.contains("Authorization"));
    }

    @Test
    void mixedUnknownAndUnavailableReasonDoesNotInventCallAttemptState() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("factStatusClassifier.judge.disabledReason", "future_judge_reason");
        meta.put("claimVerifier.judge.disabledReason", "judge_model_unavailable");

        List<StageBoundaryBreadcrumbs.BoundaryBreadcrumb> rows =
                StageBoundaryBreadcrumbs.record("final", meta);

        assertEquals(1, rows.size());
        Map<?, ?> data = rows.get(0).data();
        assertEquals("fallback", data.get("failureClass"));
        assertEquals("judge_fail_soft", data.get("reasonCode"));
        assertEquals("both", data.get("judgeLane"));
        assertFalse(data.containsKey("judgeCallAttempted"),
                "one unavailable lane does not prove the unknown lane skipped its judge call");
    }

    private static final class ThrowingTraceMap extends LinkedHashMap<String, Object> {
        private boolean failWrites;

        private ThrowingTraceMap(Map<String, Object> initial) {
            super(initial);
            this.failWrites = true;
        }

        @Override
        public Object put(String key, Object value) {
            if (failWrites) {
                throw new IllegalStateException("ownerToken=private-token");
            }
            return super.put(key, value);
        }
    }
}
