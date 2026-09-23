package com.example.lms.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceStoreMlaBreadcrumbCountTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void appendMaintainsMlaBreadcrumbCountForEveryWriter() {
        TraceStore.append("ml.breadcrumbs.v1", Map.of("component", "StageBoundaryBreadcrumbs"));

        assertEquals(1L, TraceStore.getLong("cihRag.mlaBreadcrumbCount"));

        TraceStore.append("ml.breadcrumbs.v1", Map.of("component", "ConversationBreadcrumbAspect"));

        assertEquals(2L, TraceStore.getLong("cihRag.mlaBreadcrumbCount"));
    }
}
