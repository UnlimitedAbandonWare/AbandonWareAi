package com.example.lms.llm.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class FailoverConfigurationBindingTest {
    @Test void runtimeDefaultsActivateBothCircuitAndRegisteredCloudFallback() throws Exception {
        var env = environment();
        var properties = Binder.get(env).bind("llm.gateway", LlmGatewayProperties.class).get();
        var probe = new HybridLlmGatewayProbeService(properties, null, null, new LlmRouteScorer(), env);
        assertTrue(probe.localFailoverEnabled());
        assertTrue(probe.cloudFallbackEnabled());
        assertEquals(30_000L, probe.failoverDiagnostics().get("recoveryStableMs"));
        assertTrue(env.getProperty("awx.gpu-hardware.telemetry.enabled", Boolean.class, false));
    }
    @Test void explicitOperatorDisableIsPreserved() throws Exception {
        var env = environment().withProperty("LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_ENABLED", "false")
                .withProperty("LLM_GATEWAY_CLOUD_ENABLED", "false");
        var properties = Binder.get(env).bind("llm.gateway", LlmGatewayProperties.class).get();
        var probe = new HybridLlmGatewayProbeService(properties, null, null, new LlmRouteScorer(), env);
        assertFalse(probe.localFailoverEnabled());
        assertFalse(probe.cloudFallbackEnabled());
    }
    private static MockEnvironment environment() throws Exception {
        var env = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("llm", new FileSystemResource("main/resources/application-llm.yaml")))
            env.getPropertySources().addLast(source);
        return env;
    }
}
