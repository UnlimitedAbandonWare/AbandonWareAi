package com.example.lms.debug;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DebugEventStoreConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void zeroRingCapacityFailsStartupWithFixedReason() {
        assertInvalidCapacity("0");
    }

    @Test
    void negativeRingCapacityFailsStartupWithFixedReason() {
        assertInvalidCapacity("-1");
    }

    private void assertInvalidCapacity(String configuredCapacity) {
        contextRunner
                .withPropertyValues(
                        "lms.debug.events.max-size=" + configuredCapacity,
                        "abandonware.debug.ndjson.enabled=false")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "invalid debug capacity must fail initialization");
                    Throwable root = failure;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    assertEquals("debug_event_capacity_invalid", root.getMessage());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DebugEventStore.class)
    static class TestConfiguration {
    }
}
