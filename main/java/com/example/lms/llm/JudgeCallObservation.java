package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One bounded judge attempt. Only the executing thread binds it; terminal snapshots are immutable. */
public final class JudgeCallObservation {
    public static final String TRACE_KEY = "judge.call.observations";
    private static final ThreadLocal<JudgeCallObservation> CURRENT = new ThreadLocal<>();
    private final String lane;
    private final String attemptId = UUID.randomUUID().toString();
    private boolean invocationStarted;
    private Integer httpAttemptCount;
    private Integer httpStatus;
    private String responseModel;
    private String failureClass;
    private String reason = "invocation_not_observed";
    private String verdictSource = "none";
    private boolean outcomeKnown;
    private Map<String, Object> terminal;

    private JudgeCallObservation(String lane) {
        if (!lane.equals("fact_status_classifier") && !lane.equals("claim_extraction") && !lane.equals("claim_judgment"))
            throw new IllegalArgumentException("unsupported judge lane");
        this.lane = lane;
    }
    public static JudgeCallObservation start(String lane) {
        JudgeCallObservation observation = new JudgeCallObservation(lane);
        observation.publish(observation.snapshot());
        return observation;
    }
    public static void notRun(String lane, String reason) {
        JudgeCallObservation observation = start(lane);
        observation.skipped(reason);
        observation.finish("none", false, reason);
    }
    public static JudgeCallObservation current() { return CURRENT.get(); }
    public Scope bind() {
        JudgeCallObservation previous = CURRENT.get();
        CURRENT.set(this);
        return new Scope(previous);
    }
    public synchronized void invocationStarted() {
        if (terminal != null) return;
        invocationStarted = true;
        reason = "http_attempt_not_observed";
    }
    public synchronized void skipped(String why) {
        if (terminal != null || invocationStarted) return;
        httpAttemptCount = 0;
        reason = label(why);
    }
    public synchronized void wireStarted() {
        if (terminal != null) return;
        httpAttemptCount = httpAttemptCount == null ? 1 : httpAttemptCount + 1;
        reason = "response_not_observed";
    }
    public synchronized void wireStatus(int status) {
        if (terminal == null && status >= 100 && status <= 599) httpStatus = status;
    }
    public synchronized void responseReceived(ChatResponse response) {
        if (terminal != null) return;
        String model = response == null || response.metadata() == null ? null : response.metadata().modelName();
        responseModel = model != null && model.matches("[A-Za-z0-9][A-Za-z0-9._:/+@-]{0,199}") ? model : null;
        reason = response == null || response.aiMessage() == null || response.aiMessage().text() == null
                || response.aiMessage().text().isBlank() ? "judge_empty_response"
                : responseModel == null ? "response_model_missing" : "response_provider_not_observed";
    }
    public synchronized void failed(Throwable failure) {
        if (terminal != null) return;
        Throwable cursor = failure;
        for (int i = 0; cursor != null && i < 16; i++) {
            if (cursor instanceof dev.langchain4j.exception.HttpException http) {
                wireStatus(http.statusCode());
                reason = "judge_http_" + http.statusCode();
            }
            if (cursor instanceof java.util.concurrent.TimeoutException) reason = "judge_timeout";
            if (cursor instanceof java.util.concurrent.CancellationException) reason = "judge_cancelled";
            if (cursor.getCause() == cursor) break;
            cursor = cursor.getCause();
        }
        failureClass = failure == null ? "unknown" : label(failure.getClass().getSimpleName());
        if (!"judge_empty_response".equals(reason) && httpAttemptCount == null && !invocationStarted)
            reason = "invocation_not_observed";
    }
    public synchronized Map<String, Object> finish(String source, boolean known, String why) {
        if (terminal == null) {
            verdictSource = label(source);
            outcomeKnown = known;
            if (why != null && !("judge_call_failed".equals(why) &&
                    (reason.startsWith("judge_http_") || java.util.List.of("executor_saturated",
                            "request_budget_exhausted", "judge_timeout", "judge_cancelled", "judge_empty_response").contains(reason))))
                reason = label(why);
            terminal = snapshot();
            publish(terminal);
        }
        return terminal;
    }
    public synchronized Map<String, Object> snapshot() {
        if (terminal != null) return terminal;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lane", lane);row.put("attemptId", attemptId);
        row.put("invocationStarted", invocationStarted);row.put("httpAttemptCount", httpAttemptCount);
        row.put("httpStatus", httpStatus);row.put("responseModel", responseModel);
        // Transport endpoints and configured/model-class identities do not prove the upstream provider.
        row.put("observedProvider", null);
        row.put("observationSource", httpAttemptCount != null && httpAttemptCount > 0 ? "http_execute"
                : responseModel != null ? "chat_response_metadata" : "invocation_scope");
        row.put("failureClass", failureClass);row.put("reason", reason);
        row.put("verdictSource", verdictSource);row.put("outcomeKnown", outcomeKnown);
        row.put("httpReason", httpAttemptCount == null ? "http_attempt_not_observed" : null);
        row.put("providerReason", "upstream_provider_not_observed");
        row.put("modelReason", responseModel == null ? "response_model_not_observed" : null);
        return Collections.unmodifiableMap(row);
    }
    private void publish(Map<String, Object> row) {
        Map<String, Object> lanes = new LinkedHashMap<>();
        Object existing = TraceStore.get(TRACE_KEY);
        if (existing instanceof Map<?, ?> values) {
            for (String key : java.util.List.of("fact_status_classifier", "claim_extraction", "claim_judgment")) {
                if (values.get(key) instanceof Map<?, ?> receipt) lanes.put(key, receipt);
            }
        }
        lanes.put(lane, row);
        TraceStore.put(TRACE_KEY, Collections.unmodifiableMap(lanes));
    }
    private static String label(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,80}") ? value : "unknown";
    }
    public static final class Scope implements AutoCloseable {
        private final JudgeCallObservation previous;
        private boolean closed;
        private Scope(JudgeCallObservation previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
