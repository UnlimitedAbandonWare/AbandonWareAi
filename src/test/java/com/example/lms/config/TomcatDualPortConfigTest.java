package com.example.lms.config;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomcatDualPortConfigTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidFileKeyStoreUriLeavesRedactedTraceBreadcrumb() throws Exception {
        Method method = TomcatDualPortConfig.class.getDeclaredMethod("tomcatKeyStoreFile", String.class);
        method.setAccessible(true);

        String normalized = (String) method.invoke(null, "file:%");

        assertEquals("file:%", normalized);
        assertEquals("keystore.file", TraceStore.get("tomcat.ssl.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("tomcat.ssl.suppressed.errorType"));
        assertEquals(true, TraceStore.get("tomcat.ssl.suppressed.keystore.file"));
        assertEquals(6, TraceStore.get("tomcat.ssl.suppressed.inputLength"));
        assertTrue(TraceStore.get("tomcat.ssl.suppressed.inputHash") instanceof String);
        assertNull(TraceStore.get("tomcat.ssl.suppressed.rawInput"));
    }
}
