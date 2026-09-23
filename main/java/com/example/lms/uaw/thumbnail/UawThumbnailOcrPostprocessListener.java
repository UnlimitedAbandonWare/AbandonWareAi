package com.example.lms.uaw.thumbnail;

import com.abandonware.ai.addons.ocr.OcrDocument;
import com.abandonware.ai.addons.ocr.OcrRetriever;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component("uawThumbnailOcrPostprocessListener")
@ConditionalOnProperty(
        prefix = "uaw.thumbnail.ocr-postprocess",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class UawThumbnailOcrPostprocessListener {

    private static final Logger log = LoggerFactory.getLogger(UawThumbnailOcrPostprocessListener.class);
    private static final String TRACE_PREFIX = "uaw.thumbnail.ocrPostprocess.";
    private static final String SOURCE = "uaw_thumbnail_ocr_postprocess";

    private final UawThumbnailOcrPostprocessProperties properties;
    private final ObjectProvider<OcrRetriever.OcrIndexPort> indexPortProvider;

    public UawThumbnailOcrPostprocessListener(
            UawThumbnailOcrPostprocessProperties properties,
            ObjectProvider<OcrRetriever.OcrIndexPort> indexPortProvider) {
        this.properties = properties;
        this.indexPortProvider = indexPortProvider;
    }

    @EventListener
    public void captureThumbnail(UawThumbnailPersistedEvent event) {
        recordBase(true, false, 0, 0, "", null);
        if (event == null || event.graphText().isBlank()) {
            recordBase(true, false, 0, 0, "empty_event", null);
            return;
        }

        String text = clamp(event.graphText(), effectiveMaxTextChars());
        String textHash12 = SafeRedactor.hash12(text);
        int spanCount = spanCount(event);

        if (!properties.isIndexEnabled()) {
            recordBase(true, false, spanCount, 0, "index_disabled", textHash12);
            return;
        }

        OcrRetriever.OcrIndexPort indexPort = indexPortProvider == null ? null : indexPortProvider.getIfAvailable();
        if (indexPort == null) {
            recordBase(true, true, spanCount, 0, "missing_ocr_index_port", textHash12);
            log.debug("[AWX][uaw][thumbnail][ocr-postprocess] skipped reason=missing_ocr_index_port textHash={}",
                    textHash12);
            return;
        }

        try {
            indexPort.index(new OcrDocument("uaw-thumb-ocr:" + textHash12, text, SOURCE));
            recordBase(true, true, spanCount, 1, "indexed", textHash12);
        } catch (Exception ex) {
            recordBase(true, true, spanCount, 0, ex.getClass().getSimpleName(), textHash12);
            log.debug("[AWX][uaw][thumbnail][ocr-postprocess] skipped failureClass={} textHash={}",
                    ex.getClass().getSimpleName(), textHash12);
        }
    }

    private static void recordBase(
            boolean enabled,
            boolean attempted,
            int spanCount,
            int indexedCount,
            String failureClass,
            String textHash12) {
        TraceStore.put(TRACE_PREFIX + "enabled", enabled);
        TraceStore.put(TRACE_PREFIX + "attempted", attempted);
        TraceStore.put(TRACE_PREFIX + "spanCount", Math.max(0, spanCount));
        TraceStore.put(TRACE_PREFIX + "indexedCount", Math.max(0, indexedCount));
        TraceStore.put(TRACE_PREFIX + "failureClass", safeFailureClass(failureClass));
        if (textHash12 != null && !textHash12.isBlank()) {
            TraceStore.put(TRACE_PREFIX + "textHash12", textHash12);
        }
    }

    private static String safeFailureClass(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private int spanCount(UawThumbnailPersistedEvent event) {
        int anchors = event.anchors() == null ? 0 : event.anchors().size();
        int count = (event.caption() == null || event.caption().isBlank()) ? anchors : anchors + 1;
        return Math.min(Math.max(1, properties.getMaxSpans()), Math.max(0, count));
    }

    private int effectiveMaxTextChars() {
        return Math.max(200, properties.getMaxTextChars());
    }

    private static String clamp(String value, int maxChars) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars)).trim();
    }
}
