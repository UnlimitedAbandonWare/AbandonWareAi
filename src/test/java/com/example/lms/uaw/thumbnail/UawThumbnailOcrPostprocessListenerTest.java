package com.example.lms.uaw.thumbnail;

import com.abandonware.ai.addons.ocr.OcrDocument;
import com.abandonware.ai.addons.ocr.OcrRetriever;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UawThumbnailOcrPostprocessListenerTest {

    private static final String RAW_THUMBNAIL_TEXT = "private OCR thumbnail text about a contract";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PostprocessConfig.class)
            .withBean(OcrIndexPortCapture.class);

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void postprocessListenerIsDisabledByDefault() {
        contextRunner.run(context -> assertFalse(context.containsBean("uawThumbnailOcrPostprocessListener")));
    }

    @Test
    void enabledPostprocessIndexesThroughOcrPortAndRecordsOnlyHashTrace() {
        contextRunner
                .withUserConfiguration(UawThumbnailOcrPostprocessListener.class)
                .withPropertyValues(
                        "uaw.thumbnail.ocr-postprocess.enabled=true",
                        "uaw.thumbnail.ocr-postprocess.index-enabled=true")
                .run(context -> {
                    UawThumbnailOcrPostprocessListener listener = context.getBean(UawThumbnailOcrPostprocessListener.class);
                    OcrIndexPortCapture capture = context.getBean(OcrIndexPortCapture.class);

                    listener.captureThumbnail(new UawThumbnailPersistedEvent(
                            "UAW_thumbnail.v1",
                            "UAW_THUMB",
                            "THUMBNAIL",
                            RAW_THUMBNAIL_TEXT,
                            List.of("invoice image anchor", "tesseract shadow cue"),
                            0.7d));

                    assertEquals(1, capture.docs.size());
                    OcrDocument doc = capture.docs.get(0);
                    assertTrue(doc.id().startsWith("uaw-thumb-ocr:"));
                    assertEquals("uaw_thumbnail_ocr_postprocess", doc.source());
                    assertTrue(doc.text().contains(RAW_THUMBNAIL_TEXT));

                    assertEquals(true, TraceStore.get("uaw.thumbnail.ocrPostprocess.enabled"));
                    assertEquals(true, TraceStore.get("uaw.thumbnail.ocrPostprocess.attempted"));
                    assertEquals(3, TraceStore.get("uaw.thumbnail.ocrPostprocess.spanCount"));
                    assertEquals(1, TraceStore.get("uaw.thumbnail.ocrPostprocess.indexedCount"));
                    assertNotNull(TraceStore.get("uaw.thumbnail.ocrPostprocess.textHash12"));
                    assertEquals("indexed", TraceStore.get("uaw.thumbnail.ocrPostprocess.failureClass"));

                    String publicTrace = TraceStore.getAll().toString();
                    assertFalse(publicTrace.contains(RAW_THUMBNAIL_TEXT));
                    assertFalse(publicTrace.contains("invoice image anchor"));
                    assertFalse(publicTrace.contains("tesseract shadow cue"));
                });
    }

    @Test
    void enabledPostprocessFailsSoftWhenIndexPortIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(PostprocessConfig.class)
                .withPropertyValues(
                        "uaw.thumbnail.ocr-postprocess.enabled=true",
                        "uaw.thumbnail.ocr-postprocess.index-enabled=true")
                .run(context -> {
                    UawThumbnailOcrPostprocessListener listener = context.getBean(UawThumbnailOcrPostprocessListener.class);

                    listener.captureThumbnail(new UawThumbnailPersistedEvent(
                            "UAW_thumbnail.v1",
                            "UAW_THUMB",
                            "THUMBNAIL",
                            RAW_THUMBNAIL_TEXT,
                            List.of("anchor one"),
                            0.7d));

                    assertEquals(true, TraceStore.get("uaw.thumbnail.ocrPostprocess.enabled"));
                    assertEquals(true, TraceStore.get("uaw.thumbnail.ocrPostprocess.attempted"));
                    assertEquals(0, TraceStore.get("uaw.thumbnail.ocrPostprocess.indexedCount"));
                    assertEquals("missing_ocr_index_port", TraceStore.get("uaw.thumbnail.ocrPostprocess.failureClass"));
                    assertFalse(TraceStore.getAll().toString().contains(RAW_THUMBNAIL_TEXT));
                });
    }

    static class OcrIndexPortCapture implements OcrRetriever.OcrIndexPort {
        final List<OcrDocument> docs = new ArrayList<>();

        @Override
        public void index(OcrDocument doc) {
            docs.add(assertInstanceOf(OcrDocument.class, doc));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UawThumbnailOcrPostprocessProperties.class)
    @Import(UawThumbnailOcrPostprocessListener.class)
    static class PostprocessConfig {
    }
}
