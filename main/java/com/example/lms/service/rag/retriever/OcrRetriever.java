package com.example.lms.service.rag.retriever;

import com.abandonware.ai.addons.synthesis.ContextItem;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter that exposes the Addons OCR retriever as a LangChain4j {@link ContentRetriever}.
 *
 * <p>
 * The underlying implementation is provided by {@code com.abandonware.ai.addons.ocr.OcrRetriever}
 * (auto-configured when AddonsAutoConfiguration is on the classpath). This adapter converts
 * {@link ContextItem} results into {@link Content} instances with metadata so downstream pipelines
 * can dedupe/trace as needed.
 * </p>
 *
 * <p>
 * This is a best-effort auxiliary axis: it is disabled by default and always fail-soft.
 * </p>
 */
@Component
public class OcrRetriever implements ContentRetriever {

    private static final System.Logger LOG = System.getLogger(OcrRetriever.class.getName());

    private final ObjectProvider<com.abandonware.ai.addons.ocr.OcrRetriever> delegateProvider;

    private final boolean enabled;
    private final boolean required;
    private final int topK;
    private final int maxChars;

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    @Autowired(required = false)
    private FaultMaskingLayerMonitor faultMaskingLayerMonitor;

    public OcrRetriever(
            ObjectProvider<com.abandonware.ai.addons.ocr.OcrRetriever> delegateProvider,
            @Value("${rag.ocr.enabled:false}") boolean enabled,
            @Value("${rag.ocr.required:false}") boolean required,
            @Value("${rag.ocr.top-k:${rag.ocr.topK:6}}") int topK,
            @Value("${rag.ocr.max-chars:${rag.ocr.maxChars:1200}}") int maxChars
    ) {
        this.delegateProvider = delegateProvider;
        this.enabled = enabled;
        this.required = required;
        this.topK = Math.max(0, topK);
        // Keep at least a reasonable bound even when configured too low.
        this.maxChars = Math.max(200, maxChars);
    }

    @jakarta.annotation.PostConstruct
    void validateRequiredDependency() {
        if (!enabled || !required) {
            return;
        }
        if (delegateProvider == null || delegateProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "rag.ocr.required=true but com.abandonware.ai.addons.ocr.OcrRetriever bean is missing");
        }
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (!enabled) {
            recordDependency("disabled", required, false, false, "disabled_by_config", null, null);
            return List.of();
        }

        String q = (query == null) ? null : query.text();
        com.abandonware.ai.addons.ocr.OcrRetriever delegate =
                (delegateProvider == null) ? null : delegateProvider.getIfAvailable();
        if (delegate == null) {
            IllegalStateException missing = new IllegalStateException("OCR delegate bean missing");
            recordDependency("missing_delegate", required, true, true, "missing-dependency", missing, q);
            TraceStore.append("ocr.skip", "no_delegate");
            return List.of();
        }

        if (q == null || q.isBlank()) {
            recordDependency("ready", required, false, false, "", null, null);
            return List.of();
        }

        try {
            recordDependency("ready", required, true, false, "", null, q);
            List<ContextItem> items = delegate.retrieve(q);
            if (items == null || items.isEmpty()) {
                TraceStore.put("ocr.hits", 0);
                return List.of();
            }

            int limit = (topK <= 0) ? items.size() : Math.min(topK, items.size());
            List<Content> out = new ArrayList<>(limit);

            for (int i = 0; i < items.size() && out.size() < limit; i++) {
                ContextItem item = items.get(i);
                if (item == null) continue;

                String snippet = firstNonBlank(item.snippet(), item.title());
                if (snippet.isBlank()) continue;

                String text = clamp(snippet, maxChars);

                Map<String, Object> meta = new HashMap<>();
                meta.put("source", "ocr");
                meta.put("ocr_id", item.id());
                meta.put("ocr_title", item.title());
                meta.put("ocr_source", item.source());
                meta.put("ocr_rank", item.rank());
                meta.put("ocr_score", item.score());

                // Best-effort URL passthrough (if the upstream provides it via meta)
                try {
                    if (item.meta() != null && item.meta().get("url") != null) {
                        meta.put("url", String.valueOf(item.meta().get("url")));
                    }
                } catch (Exception ex) {
                    String errorType = SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
                    TraceStore.put("ocr.suppressed.stage", "urlMeta");
                    TraceStore.put("ocr.suppressed.errorType", errorType);
                    TraceStore.put("ocr.suppressed.urlMeta", true);
                    TraceStore.put("ocr.suppressed.urlMeta.errorType", errorType);
                    LOG.log(System.Logger.Level.DEBUG,
                            "OCR metadata passthrough skipped stage=url_meta errorType="
                                    + ex.getClass().getSimpleName());
                }

                out.add(Content.from(TextSegment.from(text, Metadata.from(meta))));
            }

            TraceStore.put("ocr.hits", out.size());
            return out;
        } catch (Exception e) {
            // OCR is auxiliary: never break the whole retrieval chain.
            recordDependency("failed", required, true, true, classifyFailure(e), e, q);
            TraceStore.append("ocr.fail", classifyFailure(e));
            return List.of();
        }
    }

    private void recordDependency(String status, boolean requiredValue, boolean attempted,
            boolean fallbackUsed, String failureClass, Throwable error, String query) {
        String axis = "ocr";
        String safeStatus = (status == null || status.isBlank()) ? "unknown" : status;
        String safeFailure = (failureClass == null) ? "" : failureClass;
        try {
            TraceStore.put("retrieval.dependency." + axis + ".status", safeStatus);
            TraceStore.put("retrieval.dependency." + axis + ".required", requiredValue);
            TraceStore.put("retrieval.dependency." + axis + ".attempted", attempted);
            TraceStore.put("retrieval.dependency." + axis + ".failureClass", safeFailure);
            TraceStore.put("retrieval.dependency." + axis + ".fallbackUsed", fallbackUsed);
            if (query != null && !query.isBlank()) {
                TraceStore.put("retrieval.dependency." + axis + ".queryHash12", SafeRedactor.hash12(query));
            }
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("axis", axis);
            event.put("status", safeStatus);
            event.put("required", requiredValue);
            event.put("attempted", attempted);
            event.put("failureClass", safeFailure);
            event.put("fallbackUsed", fallbackUsed);
            TraceStore.append("retrieval.dependency.events", event);
        } catch (Exception e) {
            String errorType = SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown");
            TraceStore.put("ocr.suppressed.stage", "dependencyTrace");
            TraceStore.put("ocr.suppressed.errorType", errorType);
            TraceStore.put("ocr.suppressed.dependencyTrace", true);
            TraceStore.put("ocr.suppressed.dependencyTrace.errorType", errorType);
        }
        if (faultMaskingLayerMonitor != null && (error != null || fallbackUsed)) {
            try {
                faultMaskingLayerMonitor.record(
                        "retrieval.dependency." + axis,
                        error == null ? new IllegalStateException(safeStatus) : error,
                        "axis=" + axis,
                        safeStatus);
            } catch (Exception e) {
                String errorType = SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown");
                TraceStore.put("ocr.suppressed.stage", "faultMask");
                TraceStore.put("ocr.suppressed.errorType", errorType);
                TraceStore.put("ocr.suppressed.faultMask", true);
                TraceStore.put("ocr.suppressed.faultMask.errorType", errorType);
            }
        }
        if (debugEventStore != null && (error != null || fallbackUsed || requiredValue)) {
            try {
                Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("axis", axis);
                data.put("status", safeStatus);
                data.put("required", requiredValue);
                data.put("attempted", attempted);
                data.put("failureClass", safeFailure);
                data.put("fallbackUsed", fallbackUsed);
                debugEventStore.emit(
                        (error != null || fallbackUsed) ? DebugProbeType.FAULT_MASK : DebugProbeType.ORCHESTRATION,
                        (error != null || fallbackUsed) ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                        "retrieval.dependency." + axis + "." + safeStatus,
                        "Retrieval dependency state recorded",
                        "OcrRetriever.retrieve",
                        data,
                        error);
            } catch (Exception e) {
                String errorType = SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown");
                TraceStore.put("ocr.suppressed.stage", "debugEvent");
                TraceStore.put("ocr.suppressed.errorType", errorType);
                TraceStore.put("ocr.suppressed.debugEvent", true);
                TraceStore.put("ocr.suppressed.debugEvent.errorType", errorType);
            }
        }
    }

    private static String classifyFailure(Throwable error) {
        if (error == null) {
            return "";
        }
        String name = error.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        String msg = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (error instanceof java.util.concurrent.CancellationException
                || error instanceof InterruptedException
                || name.contains("cancel")
                || name.contains("interrupt")
                || msg.contains("cancelled")
                || msg.contains("canceled")
                || msg.contains("interrupted")) {
            return "cancelled";
        }
        if (name.contains("timeout") || msg.contains("timeout")) {
            return "timeout";
        }
        if (msg.contains("api key") || msg.contains("credential") || msg.contains("unauthorized")) {
            return "provider-disabled";
        }
        return "silent-failure";
    }

    private static String firstNonBlank(String a, String b) {
        String x = safeTrim(a);
        if (!x.isBlank()) return x;
        return safeTrim(b);
    }

    private static String safeTrim(String s) {
        return (s == null) ? "" : s.trim();
    }

    private static String clamp(String s, int maxChars) {
        String t = safeTrim(s);
        if (t.length() <= maxChars) return t;
        return t.substring(0, Math.max(0, maxChars)) + "…";
    }
}
