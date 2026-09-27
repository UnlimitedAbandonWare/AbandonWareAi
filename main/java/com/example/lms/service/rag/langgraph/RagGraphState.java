package com.example.lms.service.rag.langgraph;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryTrace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

public class RagGraphState extends AgentState {
    public static final String QUERY = "query";
    public static final String PLAN_ID = "planId";
    public static final String REQUEST = "request";
    public static final String TRACE = "trace";
    public static final String RESPONSE = "response";
    public static final String DEBUG = "debug";
    public static final String FAILURE_REASON = "failureReason";
    public static final String CONTROL_DECISION = "controlDecision";
    public static final String SAFETY_MODE = "safetyMode";
    public static final String RETRIEVAL_POSTURE = "retrievalPosture";
    public static final String FAILURE_ACTION = "failureAction";
    public static final String TRANSITION_TRACE = "transitionTrace";

    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(QUERY, Channels.base(() -> "")),
            Map.entry(PLAN_ID, Channels.base(() -> null)),
            Map.entry(REQUEST, Channels.base(() -> null)),
            Map.entry(TRACE, Channels.base(() -> null)),
            Map.entry(RESPONSE, Channels.base(() -> null)),
            Map.entry(DEBUG, Channels.base((Supplier<Map<String, Object>>) LinkedHashMap::new)),
            Map.entry(FAILURE_REASON, Channels.base(() -> "")),
            Map.entry(CONTROL_DECISION, Channels.base(() -> null)),
            Map.entry(SAFETY_MODE, Channels.base(() -> "")),
            Map.entry(RETRIEVAL_POSTURE, Channels.base(() -> "")),
            Map.entry(FAILURE_ACTION, Channels.base(() -> "")),
            Map.entry(TRANSITION_TRACE, Channels.appender(ArrayList::new))
    );

    public RagGraphState(Map<String, Object> initData) {
        super(initData == null ? Map.of() : new LinkedHashMap<>(initData));
    }

    public String query() {
        return value(QUERY).map(String::valueOf).orElse("");
    }

    public String planId() {
        return value(PLAN_ID).map(String::valueOf).orElse(null);
    }

    public QueryRequest request() {
        return value(REQUEST).filter(QueryRequest.class::isInstance).map(QueryRequest.class::cast).orElse(null);
    }

    public QueryTrace trace() {
        return value(TRACE).filter(QueryTrace.class::isInstance).map(QueryTrace.class::cast).orElse(null);
    }

    public QueryResponse response() {
        return value(RESPONSE).filter(QueryResponse.class::isInstance).map(QueryResponse.class::cast).orElse(null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> debug() {
        return value(DEBUG)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(m -> new LinkedHashMap<String, Object>((Map<String, Object>) m))
                .orElseGet(LinkedHashMap::new);
    }

    public String failureReason() {
        return value(FAILURE_REASON).map(String::valueOf).orElse("");
    }

    public RagGraphControlPolicy.Decision controlDecision() {
        return value(CONTROL_DECISION)
                .filter(RagGraphControlPolicy.Decision.class::isInstance)
                .map(RagGraphControlPolicy.Decision.class::cast)
                .orElse(null);
    }

    public String safetyMode() {
        return value(SAFETY_MODE).map(String::valueOf).orElse("");
    }

    public String retrievalPosture() {
        return value(RETRIEVAL_POSTURE).map(String::valueOf).orElse("");
    }

    public String failureAction() {
        return value(FAILURE_ACTION).map(String::valueOf).orElse("");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> transitionTrace() {
        return value(TRANSITION_TRACE)
                .filter(List.class::isInstance)
                .map(List.class::cast)
                .map(rows -> new ArrayList<Map<String, Object>>((List<Map<String, Object>>) rows))
                .orElseGet(ArrayList::new);
    }
}
