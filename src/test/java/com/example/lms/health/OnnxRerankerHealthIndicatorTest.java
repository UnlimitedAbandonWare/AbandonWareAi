package com.example.lms.health;

import com.example.lms.search.TraceStore;
import com.example.lms.service.onnx.OnnxRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.actuate.health.Health;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnnxRerankerHealthIndicatorTest {

    @Test
    void validationErrorDoesNotExposeRawSecrets() {
        OnnxRuntimeService service = mock(OnnxRuntimeService.class);
        String rawSecret = "" + com.example.lms.test.SecretFixtures.openAiKey() + "";
        when(service.isAvailable()).thenReturn(true);
        when(service.isFallbackEnabled()).thenReturn(true);
        when(service.scorePair("health-check", "health-check"))
                .thenThrow(new IllegalStateException("onnx validation failed api_key=" + rawSecret));
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("onnxRuntimeService", service);
        OnnxRerankerHealthIndicator indicator =
                new OnnxRerankerHealthIndicator(factory.getBeanProvider(OnnxRuntimeService.class));

        Health health = indicator.health();
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) health.getDetails().get("validation");
        String error = String.valueOf(validation.get("error"));

        assertTrue(validation.containsKey("error"));
        assertTrue(error.startsWith("hash:"), error);
        assertFalse(error.contains(rawSecret));
        assertFalse(error.contains("api_key=" + rawSecret));
    }

    @Test
    void degradedHealthExposesSanitizedDisabledReason() {
        OnnxRuntimeService service = mock(OnnxRuntimeService.class);
        when(service.isAvailable()).thenReturn(false);
        when(service.isFallbackEnabled()).thenReturn(true);
        when(service.getDisabledReason()).thenReturn("placeholder_or_too_small_model");
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("onnxRuntimeService", service);
        OnnxRerankerHealthIndicator indicator =
                new OnnxRerankerHealthIndicator(factory.getBeanProvider(OnnxRuntimeService.class));

        Health health = indicator.health();

        assertEquals("DEGRADED", health.getStatus().getCode());
        assertEquals("placeholder_or_too_small_model", health.getDetails().get("disabledReason"));
    }

    @Test
    void onnxHealthIndicatorsLeaveFixedStageBreadcrumbs() throws Exception {
        String onnxHealth = Files.readString(Path.of("main/java/com/example/lms/health/OnnxHealthIndicator.java"));
        String rerankerHealth = Files.readString(Path.of("main/java/com/example/lms/health/OnnxRerankerHealthIndicator.java"));

        assertTrue(onnxHealth.contains("traceSuppressed(\"onnx.health\", t);"));
        assertTrue(onnxHealth.contains("TraceStore.put(\"onnx.health.suppressed.\" + safeStage, true);"));
        assertTrue(rerankerHealth.contains("traceSuppressed(\"onnxReranker.healthValidation\", t);"));
        assertTrue(rerankerHealth.contains("TraceStore.put(\"onnx.reranker.health.suppressed.\" + safeStage, true);"));
    }

    @Test
    void onnxHealthSuppressionsIncludeSafeAggregateStageAndErrorType() throws Exception {
        TraceStore.clear();
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        Method onnx = OnnxHealthIndicator.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        Method reranker = OnnxRerankerHealthIndicator.class.getDeclaredMethod(
                "traceSuppressed", String.class, Throwable.class);
        onnx.setAccessible(true);
        reranker.setAccessible(true);

        onnx.invoke(null, "onnx.health " + secret, new IllegalStateException("raw " + secret));
        reranker.invoke(null, "onnxReranker.healthValidation " + secret,
                new IllegalArgumentException("raw " + secret));

        Object onnxStage = TraceStore.get("onnx.health.suppressed.stage");
        assertTrue(String.valueOf(onnxStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("onnx.health.suppressed." + onnxStage));
        assertEquals("IllegalStateException", TraceStore.get("onnx.health.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("onnx.health.suppressed." + onnxStage + ".errorType"));

        Object rerankerStage = TraceStore.get("onnx.reranker.health.suppressed.stage");
        assertTrue(String.valueOf(rerankerStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("onnx.reranker.health.suppressed." + rerankerStage));
        assertEquals("IllegalArgumentException", TraceStore.get("onnx.reranker.health.suppressed.errorType"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("onnx.reranker.health.suppressed." + rerankerStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
        TraceStore.clear();
    }
}
