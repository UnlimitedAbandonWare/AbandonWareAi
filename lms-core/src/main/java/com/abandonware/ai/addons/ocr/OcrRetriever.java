package com.abandonware.ai.addons.ocr;

import com.abandonware.ai.addons.config.AddonsProperties;
import com.abandonware.ai.addons.synthesis.ContextItem;
import java.util.*;
import java.util.logging.Logger;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.ocr.OcrRetriever
 * Role: config
 * Dependencies: com.abandonware.ai.addons.config.AddonsProperties, com.abandonware.ai.addons.synthesis.ContextItem
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.ocr.OcrRetriever
role: config
*/
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
        this.props = props; this.search = search;
    }

    public java.util.List<ContextItem> retrieve(String query) {
        if (!props.getOcr().isEnabled()) return java.util.List.of();
        int k = props.getOcr().getTopK();
        try {
            return search.search(query, k);
        } catch (Exception e) {
            log.warning("OCR retrieve failed: " + e.getMessage());
            return java.util.List.of();
        }
    }
}