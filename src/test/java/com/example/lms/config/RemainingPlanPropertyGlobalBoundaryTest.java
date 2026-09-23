package com.example.lms.config;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.abandonware.patch.config.PatchAutoConfiguration;
import com.abandonware.patch.guard.CitationGate;
import com.example.lms.infra.upstash.UpstashBackedWebCache;
import com.example.lms.infra.upstash.UpstashRedisClient;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.trace.TraceContext;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RemainingPlanPropertyGlobalBoundaryTest {
    @BeforeEach
    @AfterEach
    void clearThreadState() {
        TimeBudgetContext.clear();
        TraceContext.cleanupCurrentThread();
        TraceStore.clear();
    }

    private static ApplicationContextRunner isolated() {
        return new ApplicationContextRunner().withInitializer(context -> {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        });
    }

    @ParameterizedTest(name = "global-citation:{0}:minimum={1}")
    @CsvSource({"absent,absent", "false,absent", "true,absent", "true,5"})
    void explicitCitationConfigurationUsesGlobalActivationAndThreshold(String enabled, String minimum) {
        ApplicationContextRunner runner = isolated().withUserConfiguration(PatchAutoConfiguration.class);
        if (!"absent".equals(enabled)) runner = runner.withPropertyValues("gate.citation.enabled=" + enabled);
        if (!"absent".equals(minimum)) runner = runner.withPropertyValues("gate.citation.min=" + minimum);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            boolean expected = "true".equals(enabled);
            if (expected) {
                assertThat(context).hasSingleBean(CitationGate.class);
                CitationGate gate = context.getBean(CitationGate.class);
                int threshold = "absent".equals(minimum) ? 3 : 5;
                List<String> sources = IntStream.range(0, threshold).mapToObj(i -> "source-" + i).toList();
                assertThat(gate.pass(sources)).isTrue();
                assertThat(gate.pass(sources.subList(0, threshold - 1))).isFalse();
                assertThat(gate.pass(List.of("same", "same", "another"))).isFalse();
                assertThat(gate.pass(null)).isFalse();
            } else assertThat(context).doesNotHaveBean(CitationGate.class);
            System.out.printf("TBL07_GLOBAL_CITATION enabled=%s minimum=%s beanPresent=%s thresholdChecked=%s explicitConfiguration=true%n",
                    enabled, minimum, expected, expected);
        });
    }

    static Stream<Arguments> ttlCases() {
        var cases = new ArrayList<Arguments>();
        for (String global : List.of("absent", "45", "90"))
            for (String caller : List.of("absent", "zero", "negative", "explicit")) cases.add(Arguments.of(global, caller));
        return cases.stream();
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest(name = "global-ttl:{0}:caller={1}")
    @MethodSource("ttlCases")
    void actualGlobalTtlBindingReachesMockedSetExOnlyWhenCallerUsesFallback(String global, String caller) {
        Cache<String, String> local = mock(Cache.class);
        UpstashRedisClient redis = mock(UpstashRedisClient.class);
        when(redis.setEx(eq("ttl-boundary"), eq("{}"), any(Duration.class))).thenReturn(Mono.just(true));
        ApplicationContextRunner runner = isolated().withUserConfiguration(UpstashBackedWebCache.class)
                .withBean("webLocalCache", Cache.class, () -> local)
                .withBean(UpstashRedisClient.class, () -> redis);
        if (!"absent".equals(global)) runner = runner.withPropertyValues("upstash.cache.ttl-seconds=" + global);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            Duration supplied = switch (caller) {
                case "absent" -> null;
                case "zero" -> Duration.ZERO;
                case "negative" -> Duration.ofSeconds(-1);
                default -> Duration.ofSeconds(3);
            };
            long expectedSeconds = "explicit".equals(caller) ? 3L : "absent".equals(global) ? 600L : Long.parseLong(global);
            context.getBean(UpstashBackedWebCache.class).put("ttl-boundary", "{}", supplied).block(Duration.ofSeconds(1));
            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(redis).setEx(eq("ttl-boundary"), eq("{}"), ttl.capture());
            verifyNoMoreInteractions(redis);
            verify(local).put("ttl-boundary", "{}");
            assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(expectedSeconds));
            System.out.printf("TBL07_GLOBAL_TTL global=%s caller=%s capturedSeconds=%d setExCalls=1 callerPositiveMasksGlobal=%s%n",
                    global, caller, expectedSeconds, "explicit".equals(caller));
        });
    }

    @ParameterizedTest(name = "global-naver:{0}:sync={1}:caller={2}:cap={3}")
    @CsvSource({
            "absent,absent,absent,5000,3000,3000,3000",
            "800,absent,absent,5000,800,800,800",
            "100,absent,absent,5000,100,100,250",
            "1400,600,absent,5000,1400,600,600",
            "1400,600,400,5000,1400,600,400",
            "1400,600,400,250,1400,600,250"
    })
    void actualAnnotatedFieldResolutionFeedsSyncCalculationWithIndependentCaps(String global, String sync,
            String caller, long cap, long expectedApi, long expectedSync, long expectedWait) {
        ApplicationContextRunner runner = isolated();
        if (!"absent".equals(global)) runner = runner.withPropertyValues("naver.search.timeout-ms=" + global);
        if (!"absent".equals(sync)) runner = runner.withPropertyValues("naver.search.sync-block-timeout-ms=" + sync);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NaverSearchService service = mock(NaverSearchService.class, CALLS_REAL_METHODS);
            for (String name : List.of("apiTimeoutMs", "syncBlockTimeoutMs")) {
                var field = NaverSearchService.class.getDeclaredField(name);
                Object resolved = context.getBeanFactory().resolveDependency(new DependencyDescriptor(field, true), "naver-field-boundary");
                assertThat(resolved).isInstanceOf(Long.class);
                ReflectionTestUtils.setField(service, name, resolved);
            }
            assertThat(ReflectionTestUtils.getField(service, "apiTimeoutMs")).isEqualTo(expectedApi);
            assertThat(ReflectionTestUtils.getField(service, "syncBlockTimeoutMs")).isEqualTo(expectedSync);
            assertThat(TraceContext.current().remainingMillis()).isEqualTo(Long.MAX_VALUE);
            TimeBudget budget = mock(TimeBudget.class);
            when(budget.capWaitMillis(anyLong())).thenAnswer(call -> Math.min(cap, call.getArgument(0, Long.class)));
            TimeBudgetContext.set(budget);
            Duration override = "absent".equals(caller) ? null : Duration.ofMillis(Long.parseLong(caller));
            Duration actual = ReflectionTestUtils.invokeMethod(service, "resolveSyncBlockTimeout", override);
            assertThat(actual).isEqualTo(Duration.ofMillis(expectedWait));
            System.out.printf("TBL07_GLOBAL_NAVER global=%s sync=%s caller=%s cap=%d apiMs=%d syncMs=%d waitMs=%d actualFieldDescriptors=true wholeServiceBean=false requestExecutionInvoked=false%n",
                    global, sync, caller, cap, expectedApi, expectedSync, expectedWait);
        });
    }
}
