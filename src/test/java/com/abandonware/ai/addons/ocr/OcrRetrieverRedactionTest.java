package com.abandonware.ai.addons.ocr;

import com.abandonware.ai.addons.config.AddonsProperties;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrRetrieverRedactionTest {

    @Test
    void retrieveFailsSoftWhenPropsOrSearchPortAreMissing() {
        assertDoesNotThrow(() -> assertTrue(new OcrRetriever(null, null).retrieve("ocr query").isEmpty()));

        AddonsProperties props = new AddonsProperties();
        props.getOcr().setEnabled(true);

        assertDoesNotThrow(() -> assertTrue(new OcrRetriever(props, null).retrieve("ocr query").isEmpty()));
    }

    @Test
    void retrieveClampsNegativeTopKBeforeCallingSearchPort() {
        AddonsProperties props = new AddonsProperties();
        props.getOcr().setEnabled(true);
        props.getOcr().setTopK(-7);
        int[] capturedTopK = new int[]{-1};

        OcrRetriever retriever = new OcrRetriever(props, (query, topK) -> {
            capturedTopK[0] = topK;
            return java.util.List.of();
        });

        assertTrue(retriever.retrieve("ocr query").isEmpty());
        assertEquals(0, capturedTopK[0]);
    }

    @Test
    void retrieveFailureLogDoesNotExposeRawExceptionMessage() {
        AddonsProperties props = new AddonsProperties();
        props.getOcr().setEnabled(true);
        OcrRetriever retriever = new OcrRetriever(props, (query, topK) -> {
            throw new IllegalStateException(
                    "ocr backend failed ownerToken=" + com.example.lms.test.SecretFixtures.openAiKey() + " at C:\\Users\\nninn\\secret\\ocr.txt");
        });

        Logger logger = Logger.getLogger(OcrRetriever.class.getName());
        StringBuilder captured = new StringBuilder();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.append(record.getMessage()).append('\n');
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() throws SecurityException {
            }
        };
        Level oldLevel = logger.getLevel();
        boolean oldUseParentHandlers = logger.getUseParentHandlers();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        try {
            assertTrue(retriever.retrieve("private ocr query").isEmpty());
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(oldLevel);
            logger.setUseParentHandlers(oldUseParentHandlers);
        }

        String log = captured.toString();
        assertFalse(log.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(log.contains("C:\\Users\\nninn"));
        assertFalse(log.contains("ownerToken"));
        assertFalse(log.contains("ocr backend failed"));
        assertTrue(log.contains("errorHash="));
        assertTrue(log.contains("errorLength="));
    }

    @Test
    void retrieveFailureLeavesTraceBreadcrumbWithoutRawExceptionMessage() {
        TraceStore.clear();
        AddonsProperties props = new AddonsProperties();
        props.getOcr().setEnabled(true);
        OcrRetriever retriever = new OcrRetriever(props, (query, topK) -> {
            throw new IllegalStateException("ocr failed ownerToken=raw-ocr-token");
        });

        assertTrue(retriever.retrieve("private ocr query").isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("addons.ocr.retrieve.suppressed"));
        assertEquals("ocr_retrieve_failed", TraceStore.get("addons.ocr.retrieve.reason"));
        assertEquals("IllegalStateException", TraceStore.get("addons.ocr.retrieve.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-ocr-token"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private ocr query"));
    }
}
