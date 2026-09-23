package com.example.lms.llm.gateway;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LlmGatewayPropertiesLocalDeviceFailoverTest {

    @Test
    void localDeviceFailoverDefaultsDisabledAndObserveFirst() {
        LlmGatewayProperties properties = new LlmGatewayProperties();
        LlmGatewayProperties.LocalDeviceFailover config = properties.getLocalDeviceFailover();

        assertFalse(config.isEnabled());
        assertEquals(LlmGatewayProperties.Enforcement.OBSERVE, config.getEnforcement());
        assertEquals(600_000L, config.getHardCooldownMs());
        assertEquals(60_000L, config.getRunnerFailureWindowMs());
        assertEquals(2, config.getRunnerFailureThreshold());
        assertEquals(2, config.getRecoverySuccesses());
        assertEquals(16, config.getMaxEndpoints());

        ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                config.toEndpointQuarantinePolicy();
        assertFalse(policy.enabled());
        assertFalse(policy.enforce());
    }
}
