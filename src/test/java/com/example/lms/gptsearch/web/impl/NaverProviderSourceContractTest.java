package com.example.lms.gptsearch.web.impl;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaverProviderSourceContractTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void timestampParserOnlyCatchesNumericAndDateParseFailures() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/gptsearch/web/impl/NaverProvider.java"));
        String parserCall = "int year = Integer.parseInt(m.group(1));";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "timestamp parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 440));
        assertFalse(window.contains("catch (Exception"),
                "timestamp parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "timestamp parser must not swallow Throwable");
        assertTrue(window.contains("catch (NumberFormatException | java.time.DateTimeException"),
                "timestamp parser should only catch numeric and date parse failures");
        assertTrue(window.contains("traceSuppressed(\"naver.timestamp\", ignored);"),
                "timestamp parser should leave a fixed-stage breadcrumb when it skips malformed dates");
        assertTrue(source.contains("TraceStore.put(\"web.naver.provider.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"web.naver.provider.suppressed.errorType\", safeErrorType);"));
        assertTrue(source.contains("TraceStore.put(\"web.naver.provider.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.put(\"web.naver.provider.suppressed.\" + safeStage + \".errorType\", safeErrorType);"));
        assertTrue(source.contains("ignored instanceof java.time.DateTimeException"));
        assertTrue(source.contains("return \"invalid_date\";"));
    }

    @Test
    void malformedTimestampLeavesStableInvalidDateBreadcrumb() throws Exception {
        Method method = NaverProvider.class.getDeclaredMethod("extractTimestamp", String.class);
        method.setAccessible(true);

        Object parsed = method.invoke(null, "snippet date 2024.99.99");

        assertNull(parsed);
        assertEquals("naver.timestamp", TraceStore.get("web.naver.provider.suppressed.stage"));
        assertEquals("invalid_date", TraceStore.get("web.naver.provider.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.provider.suppressed.naver.timestamp"));
        assertEquals("invalid_date", TraceStore.get("web.naver.provider.suppressed.naver.timestamp.errorType"));
    }
}
