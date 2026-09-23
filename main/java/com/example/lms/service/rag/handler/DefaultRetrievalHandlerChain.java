package com.example.lms.service.rag.handler;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.search.TraceStore;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.location.LocationService;
import com.example.lms.location.intent.LocationIntent;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.util.Locale;
import java.util.List;



/**
 * 기본 검색 체인 구현. 순서는 명시적으로 고정되어 있으며,
 * 필요에 따라 단계가 건너뛰어질 수 있다.
 *
 * 실행 순서: Memory → Self-Ask → Analyze → Web → Vector → Repair
 */
@RequiredArgsConstructor
public class DefaultRetrievalHandlerChain implements RetrievalHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultRetrievalHandlerChain.class);

    private final com.example.lms.service.rag.handler.MemoryHandler memoryHandler;
    private final SelfAskWebSearchRetriever selfAsk;
    private final AnalyzeWebSearchRetriever analyze;
    // 新規: 어댑티브 웹 검색 핸들러 (웹 이전 단계에서 검색 필요 여부를 판단)
    private final AdaptiveWebSearchHandler adaptiveWeb;
    private final WebSearchRetriever web;
    private final LangChainRAGService rag;
    private final com.example.lms.service.rag.handler.EvidenceRepairHandler repair;
    private final QueryComplexityGate gate;

    /**
     * Service used to detect location-related intents.  When the user's query
     * is determined to be about their current location (e.g. "나 지금 어디야?"),
     * the retrieval chain will short-circuit and avoid performing any
     * expensive web or vector lookups.  A specialized chat handler can then
     * take over to generate the appropriate response.
     */
    private final LocationService locationService;

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    @Autowired(required = false)
    private FaultMaskingLayerMonitor faultMaskingLayerMonitor;

    // 미설정 시 안전하게 비우고 핸들러 내부에서 가드
    @Value("${pinecone.index.name:}")
    private String pineconeIndexName;

    @Value("${rag.search.top-k:5}")
    private int topK;

    @Override
    public void handle(Query query, List<Content> accumulator) {
        if (accumulator == null) {
            return;
        }
        // 1. 세션 메모리 로드
        Long sessionId = null;
        try {
            if (query != null && query.metadata() != null) {
                java.util.Map<String,Object> md = toMap(query.metadata());
                Object sidObj = md.get(LangChainRAGService.META_SID);
                if (sidObj != null) {
                    sessionId = Long.parseLong(String.valueOf(sidObj));
                }
            }
        } catch (Exception ex) {
            traceSuppressed("session.metadata", ex);
            // ignore
        }
        if (sessionId != null) {
            int beforeMemory = accumulator.size();
            try {
                String hist = memoryHandler.loadForSession(sessionId);
                if (hist != null && !hist.isBlank()) {
                    accumulator.add(Content.from(hist));
                    // Early-cut removed: do not terminate when reaching topK here
                }
                recordRetrievalStage("memory", true, accumulator.size() - beforeMemory, null, false);
            } catch (Exception e) {
                traceSuppressed("memory", e);
                recordRetrievalStage("memory", true, accumulator.size() - beforeMemory, e, true);
            }
        } else {
            recordRetrievalStage("memory", false, 0, null, false);
        }
        String q = (query != null && query.text() != null) ? query.text().trim() : "";
        // 2. Self-Ask: 복잡한 경우만
        boolean needSelf = false;
        try {
            needSelf = gate != null && gate.needsSelfAsk(q);
        } catch (Exception e) {
            traceSuppressed("selfask.gate", e);
            recordRetrievalStage("selfask", false, 0, e, true);
        }
        if (needSelf) {
            int beforeSelfAsk = accumulator.size();
            try {
                add(accumulator, selfAsk.retrieve(query));
                recordRetrievalStage("selfask", true, accumulator.size() - beforeSelfAsk, null, false);
            } catch (Exception e) {
                traceSuppressed("selfask.retrieve", e);
                recordRetrievalStage("selfask", true, accumulator.size() - beforeSelfAsk, e, true);
            }
        } else {
            recordRetrievalStage("selfask", false, 0, null, false);
        }
        // 3. Analyze: 모호 또는 복잡한 경우만
        boolean needAnalyze = false;
        try {
            needAnalyze = gate != null && gate.needsAnalyze(q);
        } catch (Exception e) {
            traceSuppressed("analyze.gate", e);
            recordRetrievalStage("analyze", false, 0, e, true);
        }
        if (needAnalyze) {
            int beforeAnalyze = accumulator.size();
            try {
                add(accumulator, analyze.retrieve(query));
                recordRetrievalStage("analyze", true, accumulator.size() - beforeAnalyze, null, false);
            } catch (Exception e) {
                traceSuppressed("analyze.retrieve", e);
                recordRetrievalStage("analyze", true, accumulator.size() - beforeAnalyze, e, true);
            }
        } else {
            recordRetrievalStage("analyze", false, 0, null, false);
        }
        // 3-b. Location: detect and short-circuit when the query is location-related
        try {
            // When the location service is available and consent has been granted
            // it will classify the user query into a location intent.  If the
            // intent is not NONE, then retrieval is skipped so that a dedicated
            // location handler can generate a reply without any web or vector
            // evidence.  Do not add any content to the accumulator in this case.
            if (locationService != null) {
                LocationIntent li = locationService.detectIntent(q);
                if (li != null && li != LocationIntent.NONE) {
                    // Skip retrieval entirely.  Downstream chat logic should
                    // handle location queries by consulting the location service.
                    recordRetrievalStage("web", false, 0, null, true);
                    recordRetrievalStage("vector", false, 0, null, true);
                    recordRetrievalStage("kg", false, 0, null, true);
                    recordRetrievalStage("ocr", false, 0, null, true);
                    recordRetrievalStage("repair", false, 0, null, true);
                    return;
                }
            }
        } catch (Exception e) {
            traceSuppressed("location.intent", e);
            recordRetrievalStage("location", false, 0, e, true);
        }
        // 4. Adaptive Web Search (조건 실행)
        // Determine whether to perform web retrieval based on metadata.
        // The 'useWebSearch' flag must be true and 'searchMode' must not be OFF.
        Object metadata = query != null ? query.metadata() : null;
        boolean allowWeb = mdBool(metadata, "useWebSearch", false);
        String modeStr   = mdString(metadata, "searchMode", "AUTO");
        if ("OFF".equalsIgnoreCase(String.valueOf(modeStr))) {
            allowWeb = false;
        }
        int beforeAdaptive = accumulator.size();
        try {
            if (allowWeb && adaptiveWeb != null) {
                adaptiveWeb.handle(query, accumulator);
                recordRetrievalStage("web.adaptive", true, accumulator.size() - beforeAdaptive, null, false);
                // Early-cut removed: do not return here; allow subsequent stages
            } else {
                recordRetrievalStage("web.adaptive", false, 0, null, false);
            }
        } catch (Exception e) {
            traceSuppressed("web.adaptive", e);
            recordRetrievalStage("web.adaptive", true, accumulator.size() - beforeAdaptive, e, true);
        }
        // 5. Web search - explicitly invoke the legacy WebSearchRetriever after the adaptive stage
        // to make the chain order clear (Self-Ask → Analyze → Web → Vector → Repair).  The
        // evidence returned by the adaptiveWeb stage may overlap with this call, but
        // invoking web.retrieve() here satisfies the MOE requirement to explicitly
        // sequence retrieval stages.  Failures are swallowed to maintain chain
        // robustness.
        int beforeWeb = accumulator.size();
        try {
            if (allowWeb && web != null) {
                add(accumulator, web.retrieve(query));
                recordRetrievalStage("web", true, accumulator.size() - beforeWeb, null, false);
            } else {
                try {
                    TraceStore.put("retrieval.stage.web.skipReason", "search-disabled");
                } catch (Exception ex) {
                    traceSuppressed("web.skipReason", ex);
                    // tracing is best-effort
                }
                recordRetrievalStage("web", false, 0, null, false);
            }
        } catch (Exception e) {
            traceSuppressed("web.retrieve", e);
            recordRetrievalStage("web", true, accumulator.size() - beforeWeb, e, true);
        }
        // Early-cut removed: continue to vector and repair stages regardless of accumulator size
        // 6. Vector (조건 실행)
        // Only perform vector (RAG) retrieval when the 'useRag' metadata flag is true.
        boolean allowRag = mdBool(metadata, "useRag", false);
        if (allowRag) {
            int beforeVector = accumulator.size();
            try {
                ContentRetriever vector = rag.asContentRetriever(pineconeIndexName);
                add(accumulator, vector.retrieve(query));
                recordRetrievalStage("vector", true, accumulator.size() - beforeVector, null, false);
            } catch (Exception e) {
                traceSuppressed("vector.retrieve", e);
                recordRetrievalStage("vector", true, accumulator.size() - beforeVector, e, true);
            }
        } else {
            recordRetrievalStage("vector", false, 0, null, false);
        }
        recordRetrievalStage("kg", false, 0, null, false);
        recordRetrievalStage("ocr", false, 0, null, false);
        // Early-cut removed: continue to repair stage
        // 7. Repair
        int beforeRepair = accumulator.size();
        try {
            if (repair != null) {
                add(accumulator, repair.retrieve(query));
                recordRetrievalStage("repair", true, accumulator.size() - beforeRepair, null, false);
            } else {
                recordRetrievalStage("repair", false, 0, null, false);
            }
        } catch (Exception e) {
            traceSuppressed("repair.retrieve", e);
            recordRetrievalStage("repair", true, accumulator.size() - beforeRepair, e, true);
        }
    }

    private static void add(List<Content> target, List<Content> source) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }

    private void recordRetrievalStage(String stageName, boolean attempted, int outCount,
            Throwable error, boolean fallbackUsed) {
        String stage = normalizeStageName(stageName);
        String exceptionType = error == null ? "" : error.getClass().getSimpleName();
        String failureClass = error == null ? (fallbackUsed ? "fallback" : "") : classifyFailure(error);
        try {
            TraceStore.put("retrieval.stage." + stage + ".attempted", attempted);
            TraceStore.put("retrieval.stage." + stage + ".outCount", Math.max(0, outCount));
            TraceStore.put("retrieval.stage." + stage + ".exceptionType", exceptionType);
            TraceStore.put("retrieval.stage." + stage + ".failureClass", failureClass);
            TraceStore.put("retrieval.stage." + stage + ".fallbackUsed", fallbackUsed);
            java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
            ev.put("stageName", stage);
            ev.put("attempted", attempted);
            ev.put("outCount", Math.max(0, outCount));
            ev.put("exceptionType", exceptionType);
            ev.put("failureClass", failureClass);
            ev.put("fallbackUsed", fallbackUsed);
            TraceStore.append("retrieval.stage.events", ev);
        } catch (Exception ex) {
            traceSuppressed("stage.trace", ex);
            // tracing is best-effort
        }
        if (faultMaskingLayerMonitor != null && error != null) {
            try {
                faultMaskingLayerMonitor.record("retrieval.stage." + stage, error, "stage=" + stage, "fail-soft");
            } catch (Exception ex) {
                traceSuppressed("stage.monitor", ex);
                // diagnostics must not affect retrieval
            }
        }
        if (debugEventStore != null && (error != null || fallbackUsed)) {
            try {
                java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("stageName", stage);
                data.put("attempted", attempted);
                data.put("outCount", Math.max(0, outCount));
                data.put("exceptionType", exceptionType);
                data.put("failureClass", failureClass);
                data.put("fallbackUsed", fallbackUsed);
                debugEventStore.emit(
                        error != null ? DebugProbeType.FAULT_MASK : DebugProbeType.ORCHESTRATION,
                        error != null ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                        "retrieval.stage." + stage + "." + (failureClass.isBlank() ? "ok" : failureClass),
                        "Retrieval stage completed with fail-soft diagnostics",
                        "DefaultRetrievalHandlerChain.handle",
                        data,
                        error);
            } catch (Exception ex) {
                traceSuppressed("stage.debugEvent", ex);
                // diagnostics must not affect retrieval
            }
        }
    }

    private static String normalizeStageName(String stageName) {
        if (stageName == null || stageName.isBlank()) {
            return "unknown";
        }
        return stageName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static String classifyFailure(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = root.getClass().getSimpleName();
        String msg = root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (root instanceof java.util.concurrent.CancellationException
                || root instanceof InterruptedException
                || lowerName.contains("cancel")
                || lowerName.contains("interrupt")
                || msg.contains("cancelled")
                || msg.contains("canceled")
                || msg.contains("interrupted")) {
            return "cancelled";
        }
        if (msg.contains("timeout") || lowerName.contains("timeout")) {
            return "timeout";
        }
        if (msg.contains("rate limit") || msg.contains("429")) {
            return "rate-limit";
        }
        if (msg.contains("credential") || msg.contains("api key") || msg.contains("unauthorized")) {
            return "provider-disabled";
        }
        return "silent-failure";
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> toMap(Object meta) {
        if (meta == null) return java.util.Map.of();
        if (meta instanceof dev.langchain4j.data.document.Metadata md) {
            return com.example.lms.util.MetadataUtils.toMap(md);
        }
        if (meta instanceof java.util.Map<?, ?> raw) {
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            for (java.util.Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return java.util.Map.of();
    }

    // [HARDENING] ensure SID metadata is present on every query
    private dev.langchain4j.rag.query.Query ensureSidMetadata(dev.langchain4j.rag.query.Query original, String sessionKey) {
        java.util.Map<String, Object> md = new java.util.LinkedHashMap<>(QueryUtils.metadata(original));
        md.put(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey);
        return QueryUtils.buildQuery(original.text(), md);
    }

    /**
     * Safely read a boolean value from metadata.  When the key is absent
     * or the value cannot be parsed, the provided default is returned.
     *
     * @param md     metadata object, may be null
     * @param k      key name
     * @param defVal default value when parsing fails
     * @return the parsed boolean or defVal when missing/invalid
     */
    private static boolean mdBool(Object meta, String k, boolean defVal) {
        try {
            var map = toMap(meta);
            Object v = map.get(k);
            if (v instanceof Boolean b) return b;
            if (v != null)          return Boolean.parseBoolean(String.valueOf(v));
            return defVal;
        } catch (Exception e) {
            traceSuppressed("metadata.bool", e);
            return defVal;
        }
    }

    /**
     * Safely read a string value from metadata.  When the key is absent
     * or the value is null, the provided default is returned.
     *
     * @param md     metadata object, may be null
     * @param k      key name
     * @param defVal default value when missing
     * @return the string representation of the value or defVal when missing/invalid
     */
    private static String mdString(Object meta, String k, String defVal) {
        try {
            var map = toMap(meta);
            Object v = map.get(k);
            return (v != null) ? String.valueOf(v) : defVal;
        } catch (Exception e) {
            traceSuppressed("metadata.string", e);
            return defVal;
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        if (log.isDebugEnabled()) {
            log.debug("[DefaultRetrievalHandlerChain] fail-soft stage={} err={}",
                    normalizeStageName(stage),
                    classifyFailure(error));
        }
    }

}
