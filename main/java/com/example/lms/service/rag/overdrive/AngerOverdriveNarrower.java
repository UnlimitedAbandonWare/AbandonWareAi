package com.example.lms.service.rag.overdrive;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AngerOverdriveNarrower {

    private static final Logger log = LoggerFactory.getLogger(AngerOverdriveNarrower.class);

    private final CrossEncoderReranker reranker;

    @Autowired
    public AngerOverdriveNarrower(ObjectProvider<CrossEncoderReranker> rerankerProvider) {
        this(resolveReranker(rerankerProvider));
    }

    public AngerOverdriveNarrower(@Qualifier("crossEncoderReranker") CrossEncoderReranker reranker) {
        this.reranker = reranker;
    }

    public List<Content> narrow(String userQuery, List<Content> current) {
        if (current == null || current.isEmpty()) {
            traceNarrow(0, 0, true, "empty_original_returned", "empty");
            return Collections.emptyList();
        }
        if (reranker == null) {
            traceNarrow(current.size(), current.size(), true, "reranker_missing_original_returned", "noop_passthrough");
            return current;
        }
        try {
            int topN = Math.min(10, current.size());
            List<Content> reranked = reranker.rerank(userQuery == null ? "" : userQuery, current, topN);
            if (reranked == null || reranked.isEmpty()) {
                traceNarrow(current.size(), current.size(), true, "reranker_empty_original_returned", "empty_result");
                return current;
            }
            List<Content> narrowed = reranked.subList(0, Math.min(8, reranked.size()));
            traceNarrow(current.size(), narrowed.size(), false, "reranked", "cross_encoder");
            return narrowed;
        } catch (Exception e) {
            log.warn("[Overdrive] CrossEncoder rerank failed, keeping original candidates. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            traceNarrow(current.size(), current.size(), true, "exception_original_returned", "exception");
            return current;
        }
    }

    private static CrossEncoderReranker resolveReranker(ObjectProvider<CrossEncoderReranker> provider) {
        if (provider == null) {
            return null;
        }
        try {
            return provider.getIfUnique();
        } catch (RuntimeException ex) {
            log.debug("[Overdrive] reranker provider unavailable stage=narrow.provider err={} type={}",
                    "provider-unavailable", ex.getClass().getSimpleName());
            return null;
        }
    }

    private static void traceNarrow(int inputCount, int outputCount, boolean failSoft, String reason, String source) {
        try {
            String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
            TraceStore.put("overdrive.narrow.input.count", Math.max(0, inputCount));
            TraceStore.put("overdrive.narrow.output.count", Math.max(0, outputCount));
            TraceStore.put("overdrive.narrow.failSoft", failSoft);
            TraceStore.put("overdrive.narrow.reranker.source", SafeRedactor.traceLabelOrFallback(source, "unknown"));
            TraceStore.put("overdrive.narrow.reason", safeReason);
            TraceStore.put("overdrive.anchor.error", safeReason);
            TraceStore.put("overdrive.anchor.narrowed.k", Math.max(0, outputCount));
            TraceStore.put("overdrive.anchor.narrowedReason", safeReason);
            TraceStore.put("overdrive.anchor.skipReason", failSoft ? safeReason : "");
        } catch (RuntimeException ex) {
            log.debug("[Overdrive] narrow trace failed stage=narrow.trace err={} type={}",
                    "trace-failure", ex.getClass().getSimpleName());
            safeHash(reason);
        }
    }

    private static String safeHash(String value) {
        try {
            return SafeRedactor.hash12(value == null ? "" : value);
        } catch (RuntimeException ex) {
            log.debug("[Overdrive] narrow hash failed stage=narrow.hash err={} type={}",
                    "trace-failure", ex.getClass().getSimpleName());
            return "";
        }
    }

    private static String messageOf(Throwable error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private static int messageLength(Throwable error) {
        return messageOf(error).length();
    }
}
