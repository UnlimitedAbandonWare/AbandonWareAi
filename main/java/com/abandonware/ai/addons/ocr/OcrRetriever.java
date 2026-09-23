package com.abandonware.ai.addons.ocr;

import com.abandonware.ai.addons.config.AddonsProperties;
import com.abandonware.ai.addons.synthesis.ContextItem;
import com.example.lms.search.TraceStore;
import java.util.*;
import java.util.logging.Logger;




public class OcrRetriever {
    private static final Logger log = Logger.getLogger(OcrRetriever.class.getName());

    public interface OcrIndexPort {
        void index(OcrDocument doc);
    }
    public interface OcrSearchPort {
        java.util.List<ContextItem> search(String query, int topK);
    }

    private final AddonsProperties props;
    private final OcrSearchPort search;

    public OcrRetriever(AddonsProperties props, OcrSearchPort search) {
        this.props = props == null ? new AddonsProperties() : props;
        this.search = search;
    }

    public java.util.List<ContextItem> retrieve(String query) {
        if (!props.getOcr().isEnabled()) return java.util.List.of();
        if (search == null) return java.util.List.of();
        int k = Math.max(0, props.getOcr().getTopK());
        try {
            java.util.List<ContextItem> out = search.search(query, k);
            return out == null ? java.util.List.of() : out;
        } catch (Exception e) {
            String message = e == null ? "" : String.valueOf(e.getMessage());
            String errorType = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(
                    e == null ? "unknown" : e.getClass().getSimpleName(), "unknown");
            TraceStore.put("addons.ocr.retrieve.suppressed", true);
            TraceStore.put("addons.ocr.retrieve.reason", "ocr_retrieve_failed");
            TraceStore.put("addons.ocr.retrieve.errorType", errorType);
            log.warning("OCR retrieve failed: errorHash="
                    + com.example.lms.trace.SafeRedactor.hashValue(message)
                    + " errorLength=" + message.length());
            return java.util.List.of();
        }
    }
}
