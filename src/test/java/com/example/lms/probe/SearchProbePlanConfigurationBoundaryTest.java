package com.example.lms.probe;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchProbePlanConfigurationBoundaryTest {
    private static final String KEY = "probe.search.enabled";
    private static final String SYNTHETIC_TOKEN = "fixture-probe-boundary";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String[] PLANS = {
            "ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1",
            "ap9_cost_saver.v1", "hyper_nova.v1", "kg_first.v1", "rulebreak.v1"
    };

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> rootCases() {
        return Stream.of(PLANS).flatMap(plan -> Stream.of("true", "false", "absent")
                .map(value -> org.junit.jupiter.params.provider.Arguments.of(plan, value)));
    }

    @ParameterizedTest(name = "{0}:root={1}")
    @MethodSource("rootCases")
    void shippedRootFlagHasNoRequestProjection(String planId, String value) throws Exception {
        var applier = applier(planId, value, null);
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        assertFalse(plan.isEmpty());
        Map<String, Object> meta = new HashMap<>();
        GuardContext context = new GuardContext();
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        applier.applyToGuardContext(plan, context);
        assertFalse(plan.raw().containsKey(KEY));
        assertFalse(meta.containsKey(KEY));
        assertNull(context.getPlanOverride(KEY));

        Map<String, Object> explicitMeta = new HashMap<>();
        explicitMeta.put(KEY, Boolean.TRUE);
        GuardContext explicitContext = new GuardContext();
        explicitContext.putPlanOverride(KEY, Boolean.FALSE);
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), explicitMeta);
        applier.applyToGuardContext(plan, explicitContext);
        assertEquals(Boolean.TRUE, explicitMeta.get(KEY));
        assertEquals(Boolean.FALSE, explicitContext.getPlanOverride(KEY));
    }

    @Test
    void paramsPassthroughIsASeparateProducerLocation() throws Exception {
        var applier = applier(PLANS[0], "false", Boolean.TRUE);
        var plan = applier.load(PLANS[0]);
        assertEquals(PLANS[0], plan.planId());
        assertFalse(plan.isEmpty());
        Map<String, Object> meta = new HashMap<>();
        GuardContext context = new GuardContext();
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        applier.applyToGuardContext(plan, context);
        assertEquals(Boolean.TRUE, meta.get(KEY));
        assertEquals(Boolean.TRUE, context.getPlanOverride(KEY));
    }

    @ParameterizedTest(name = "global={0}")
    @ValueSource(strings = {"absent", "false", "true"})
    void conditionalBeanAndEndpointFollowGlobalPropertyIndependentlyOfRootPlan(String global)
            throws Exception {
        for (String rootValue : new String[]{"true", "false"}) {
            var applier = applier(PLANS[0], rootValue, null);
            var plan = applier.load(PLANS[0]);
            assertEquals(PLANS[0], plan.planId());
            assertFalse(plan.isEmpty());
            AtomicInteger calls = new AtomicInteger();
            var runner = runner(calls).withPropertyValues("probe.admin-token=" + SYNTHETIC_TOKEN);
            if (!global.equals("absent")) runner = runner.withPropertyValues(KEY + "=" + global);
            runner.run(context -> {
                assertNull(context.getStartupFailure());
                String expectedProperty = global.equals("absent") ? null : global;
                assertEquals(expectedProperty, context.getEnvironment().getProperty(KEY));
                applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), new HashMap<>());
                applier.applyToGuardContext(plan, new GuardContext());
                assertEquals(expectedProperty, context.getEnvironment().getProperty(KEY));
                assertEquals(global.equals("true") ? 1 : 0,
                        context.getBeansOfType(SearchProbeController.class).size());
                if (global.equals("true")) {
                    var mvc = MockMvcBuilders.standaloneSetup(
                            context.getBean(SearchProbeController.class)).build();
                    mvc.perform(post("/api/probe/search").contentType("application/json")
                                    .content("{}").header("X-Probe-Token", SYNTHETIC_TOKEN))
                            .andExpect(status().isOk());
                }
                assertEquals(global.equals("true") ? 1 : 0, calls.get());
            });
            TraceStore.clear();
        }
    }

    @ParameterizedTest(name = "token:{0}/{1}")
    @CsvSource({
            "absent,absent,404,0",
            "blank,absent,404,0",
            "valid,absent,401,0",
            "valid,mismatch,401,0",
            "valid,exact,200,1"
    })
    void globallyRegisteredBeanStillEnforcesTokenBeforeService(
            String configured, String presented, int expectedStatus, int expectedCalls) {
        AtomicInteger calls = new AtomicInteger();
        var runner = runner(calls).withPropertyValues(KEY + "=true");
        if (!configured.equals("absent")) {
            runner = runner.withPropertyValues("probe.admin-token="
                    + (configured.equals("blank") ? "" : SYNTHETIC_TOKEN));
        }
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(SearchProbeController.class).size());
            var mvc = MockMvcBuilders.standaloneSetup(
                    context.getBean(SearchProbeController.class)).build();
            var request = post("/api/probe/search").contentType("application/json").content("{}");
            if (!presented.equals("absent")) request.header("X-Probe-Token",
                    presented.equals("exact") ? SYNTHETIC_TOKEN : "fixture-probe-mismatch");
            mvc.perform(request).andExpect(status().is(expectedStatus));
            assertEquals(expectedCalls, calls.get());
        });
    }

    private static ApplicationContextRunner runner(AtomicInteger calls) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().remove("systemProperties");
                    context.getEnvironment().getPropertySources().remove("systemEnvironment");
                })
                .withUserConfiguration(ProbeConfiguration.class)
                .withBean(SearchProbeService.class, () -> request -> {
                    calls.incrementAndGet();
                    return new com.example.lms.probe.dto.SearchProbeResponse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SearchProbeController.class)
    static class ProbeConfiguration {
    }

    @SuppressWarnings("unchecked")
    private static PlanHintApplier applier(String planId, String value, Boolean paramsValue)
            throws Exception {
        Map<String, Object> root = YAML.readValue(
                Files.readString(Path.of("main/resources/plans", planId + ".yaml")), Map.class);
        Map<String, Object> search = (Map<String, Object>) ((Map<?, ?>) root.get("probe")).get("search");
        assertEquals(Boolean.FALSE, search.get("enabled"), "authored shipped precondition");
        if (value.equals("absent")) search.remove("enabled");
        else search.put("enabled", Boolean.valueOf(value));
        if (paramsValue != null) {
            Map<String, Object> params = new LinkedHashMap<>(
                    (Map<String, Object>) root.getOrDefault("params", Map.of()));
            params.put(KEY, paramsValue);
            root.put("params", params);
        }
        byte[] bytes = YAML.writeValueAsBytes(root);
        return new PlanHintApplier(new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                assertTrue(location.contains(planId), "unexpected fallback resource");
                return new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return planId + ".yaml";
                    }
                };
            }
        });
    }
}
