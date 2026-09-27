package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SelfAskHandler extends AbstractRetrievalHandler {

    private static final Logger log = LoggerFactory.getLogger(SelfAskHandler.class);

    private final SelfAskWebSearchRetriever retriever;
    private final OrchestrationGate gate;

    public SelfAskHandler(SelfAskWebSearchRetriever retriever, OrchestrationGate gate) {
        this.retriever = retriever;
        this.gate = gate;
    }

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            if (gate != null && !gate.allowSelfAsk(q)) {
                log.debug("[SelfAsk] skipped by orchestration gate");
                return true;
            }

            acc.addAll(retriever.retrieve(q));
            // 항상 다음 핸들러도 시도한다.
            return true;
        } catch (Exception e) {
            traceSelfAskFailure(q, e);
            log.warn("[AWX][rag][handler] selfAsk failed failureReason={} errorType={} queryHash12={} queryLength={}",
                    "selfask-handler-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hash12(q == null ? null : q.text()),
                    q == null || q.text() == null ? 0 : q.text().length());
            return true;
        }
    }

    private static void traceSelfAskFailure(Query query, Throwable error) {
        String queryText = query == null ? null : query.text();
        String queryHash12 = SafeRedactor.hash12(queryText);
        int queryLength = queryText == null ? 0 : queryText.length();
        String safeFailure = error == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");

        TraceStore.inc("retrieval.selfAsk.failureCount");
        TraceStore.put("retrieval.selfAsk.status", "failed");
        TraceStore.put("retrieval.selfAsk.enabled", true);
        TraceStore.put("retrieval.selfAsk.returnedCount", 0);
        TraceStore.put("retrieval.selfAsk.addedCount", 0);
        TraceStore.put("retrieval.selfAsk.disabledReason", "selfask-handler-error");
        TraceStore.put("retrieval.selfAsk.failureClass", safeFailure);
        TraceStore.put("retrieval.selfAsk.failSoft", true);
        TraceStore.put("retrieval.selfAsk.queryLength", queryLength);
        if (queryHash12 != null && !queryHash12.isBlank()) {
            TraceStore.put("retrieval.selfAsk.queryHash12", queryHash12);
        }

        TraceStore.put("retrieval.dependency.selfAsk.status", "failed");
        TraceStore.put("retrieval.dependency.selfAsk.failureClass", safeFailure);
        TraceStore.put("retrieval.dependency.selfAsk.fallbackUsed", true);
        if (queryHash12 != null && !queryHash12.isBlank()) {
            TraceStore.put("retrieval.dependency.selfAsk.queryHash12", queryHash12);
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("status", "failed");
        event.put("disabledReason", "selfask-handler-error");
        event.put("failureClass", safeFailure);
        event.put("returnedCount", 0);
        event.put("addedCount", 0);
        event.put("queryLength", queryLength);
        if (queryHash12 != null && !queryHash12.isBlank()) {
            event.put("queryHash12", queryHash12);
        }
        TraceStore.append("retrieval.selfAsk.events", event);
        TraceStore.append("retrieval.dependency.events", event);
    }

    // [HARDENING] ensure SID metadata is present on every query
    @SuppressWarnings("unused")
    private dev.langchain4j.rag.query.Query ensureSidMetadata(
            dev.langchain4j.rag.query.Query original,
            String sessionKey
    ) {
        java.util.Map<String, Object> md = new java.util.LinkedHashMap<>(QueryUtils.metadata(original));
        md.put(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey);

        // LangChain4j 1.0.x 에서는 (text, metadata)를 받는 public 생성자를 제공
        return QueryUtils.buildQuery(original.text(), md);
    }
}
