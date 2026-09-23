package com.example.lms.service.web;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaWikiClientRedactionContractTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void mediaWikiClientDoesNotLogRawThrowableForQueryFailures() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/web/MediaWikiClient.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.warn(\"MediaWiki query failed\", e);"));
        assertFalse(source.contains("MediaWiki query failed: {}"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[AWX][search][mediawiki] query failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(source.contains("\"mediawiki-query-error\""));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hash12(query)"));
        assertTrue(source.contains("TraceStore.put(\"web.mediawiki.suppressed.query\", true)"));
        assertTrue(source.contains("TraceStore.inc(\"web.mediawiki.suppressed.query.count\")"));
        assertTrue(source.contains("TraceStore.put(\"web.mediawiki.suppressed.query.errorType\""));
        assertTrue(source.contains("TraceStore.put(\"web.mediawiki.suppressed.urlEncode\", true)"));
        assertTrue(source.contains("TraceStore.inc(\"web.mediawiki.suppressed.urlEncode.count\")"));
        assertTrue(source.contains("TraceStore.put(\"web.mediawiki.suppressed.urlEncode.errorType\""));
    }

    @Test
    void mediaWikiUrlEncodeSuppressionStoresErrorType() throws Exception {
        Method enc = MediaWikiClient.class.getDeclaredMethod("enc", String.class);
        enc.setAccessible(true);

        Object result = enc.invoke(null, new Object[] { null });

        assertEquals(null, result);
        assertEquals(Boolean.TRUE, TraceStore.get("web.mediawiki.suppressed.urlEncode"));
        assertEquals(1L, TraceStore.get("web.mediawiki.suppressed.urlEncode.count"));
        assertEquals("NullPointerException", TraceStore.get("web.mediawiki.suppressed.urlEncode.errorType"));
    }
}
