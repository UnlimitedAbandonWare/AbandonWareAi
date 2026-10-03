package com.example.lms.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SettingsCapabilityProjectionTest {
    @Test void conditionalBeanIsNotLiveSupport() {
        var beans = new DefaultListableBeanFactory();
        var projection = new SettingsCapabilityProjection(
                new MockEnvironment().withProperty("demo.jev.choice.enabled", "true"), beans);
        var jev = projection.project(Map.of()).stream().filter(d -> d.id().equals("jev.main")).findFirst().orElseThrow();
        assertEquals(true, jev.configuredValue());
        assertEquals(false, jev.runtimeAvailable());
        assertNull(jev.observedValue());
        assertEquals(0, beans.getSingletonCount());
    }
    @Test void configuredValueIsNotObservedExecution() {
        var projection = new SettingsCapabilityProjection(new MockEnvironment().withProperty("onnx.enabled", "true"),
                new DefaultListableBeanFactory());
        var onnx = projection.project(Map.of()).stream().filter(d -> d.id().equals("rerank.onnx")).findFirst().orElseThrow();
        assertEquals(true, onnx.configuredValue());
        assertNull(onnx.effectiveValue());
        assertNull(onnx.observedValue());
        assertFalse(onnx.editable());
    }
    @Test void missingValueStaysUnknown() {
        var projection = new SettingsCapabilityProjection(new MockEnvironment(), new DefaultListableBeanFactory());
        var onnx = projection.project(Map.of()).stream().filter(d -> d.id().equals("rerank.onnx")).findFirst().orElseThrow();
        assertNull(onnx.configuredValue());
        assertEquals("unknown", onnx.sourceKind());
        assertNull(onnx.runtimeAvailable());
    }
    @Test void doesNotReflectArbitraryEnvironmentOrMetadata() throws Exception {
        var env = new MockEnvironment().withProperty("private.setting", "PRIVATE_VALUE");
        var projection = new SettingsCapabilityProjection(env, new DefaultListableBeanFactory());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                projection.project(Map.of("prompt", "PRIVATE_QUERY", "stage.onnx", "error:PRIVATE_ERROR")));
        assertFalse(json.contains("PRIVATE_VALUE")); assertFalse(json.contains("PRIVATE_QUERY"));
        assertFalse(json.contains("PRIVATE_ERROR"));
    }
}
