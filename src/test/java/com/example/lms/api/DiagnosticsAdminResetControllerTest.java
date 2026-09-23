package com.example.lms.api;

import ai.abandonware.nova.orch.web.WebFailSoftDomainStageReportService;
import com.example.lms.service.embedding.OllamaEmbeddingModel;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosticsAdminResetControllerTest {

    @Test
    void embeddingResetFailsClosedWhenAdminTokenMissing() {
        DomainProfileLoader loader = mock(DomainProfileLoader.class);
        when(loader.getAdminToken()).thenReturn("");
        OllamaEmbeddingModel ollama = mock(OllamaEmbeddingModel.class);
        EmbeddingDiagnosticsController controller = new EmbeddingDiagnosticsController(
                provider(ollama),
                provider(loader));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reset(null));

        assertEquals(403, ex.getStatusCode().value());
        verify(ollama, never()).resetFastFail();
    }

    @Test
    void embeddingResetAllowsMatchingAdminToken() {
        DomainProfileLoader loader = mock(DomainProfileLoader.class);
        when(loader.getAdminToken()).thenReturn("admin-token");
        OllamaEmbeddingModel ollama = mock(OllamaEmbeddingModel.class);
        EmbeddingDiagnosticsController controller = new EmbeddingDiagnosticsController(
                provider(ollama),
                provider(loader));

        assertEquals(Boolean.TRUE, controller.reset("admin-token").get("ok"));

        verify(ollama).resetFastFail();
    }

    @Test
    void webFailSoftResetFailsClosedWhenAdminTokenMissing() {
        DomainProfileLoader loader = mock(DomainProfileLoader.class);
        when(loader.getAdminToken()).thenReturn("");
        WebFailSoftDomainStageReportService report = mock(WebFailSoftDomainStageReportService.class);
        WebFailSoftDiagnosticsController controller = new WebFailSoftDiagnosticsController(
                provider(report),
                provider(loader));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resetDomainStageReport(null));

        assertEquals(403, ex.getStatusCode().value());
        verify(report, never()).reset();
    }

    @Test
    void webFailSoftResetAllowsMatchingAdminToken() {
        DomainProfileLoader loader = mock(DomainProfileLoader.class);
        when(loader.getAdminToken()).thenReturn("admin-token");
        WebFailSoftDomainStageReportService report = mock(WebFailSoftDomainStageReportService.class);
        WebFailSoftDiagnosticsController controller = new WebFailSoftDiagnosticsController(
                provider(report),
                provider(loader));

        assertEquals(Boolean.TRUE, controller.resetDomainStageReport("admin-token").get("ok"));

        verify(report).reset();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
