package com.example.lms.ai.moe;

import com.example.lms.search.TraceStore;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MoeCandidateRouterTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void selectModelPublishesRedactedCandidateTrace() {
        MoeRoutingProperties properties = new MoeRoutingProperties();
        properties.setForceHighest(true);
        properties.getAllow().put("rag", List.of("mini", "pro"));
        properties.getTierOrder().put("rag", List.of("pro", "mini"));

        String selected = new MoeCandidateRouter(properties)
                .selectModel(null, "rag", List.of("mini", "pro"));

        assertEquals("pro", selected);
        assertEquals("pro", TraceStore.get("moe.candidate.selected"));
        assertEquals("rag", TraceStore.get("moe.candidate.capability"));
        assertEquals(2, TraceStore.get("moe.candidate.totalCandidates"));
        assertEquals("", TraceStore.get("moe.candidate.bypassReason"));
    }

    @Test
    void componentScanRegistersRouterAndProperties() {
        new ApplicationContextRunner()
                .withUserConfiguration(MoeRouterScanConfig.class)
                .run(context -> {
                    assertNotNull(context.getBean(MoeRoutingProperties.class));
                    assertNotNull(context.getBean(MoeCandidateRouter.class));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = MoeCandidateRouter.class)
    static class MoeRouterScanConfig {
    }
}
