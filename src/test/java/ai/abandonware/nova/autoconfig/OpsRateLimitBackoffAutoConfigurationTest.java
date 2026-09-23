package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.orch.aop.HybridWebSearchEmptyFallbackAspect;
import ai.abandonware.nova.orch.aop.ProviderRateLimitBackoffAspect;
import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import ai.abandonware.nova.orch.web.brave.BraveRateLimitState;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class OpsRateLimitBackoffAutoConfigurationTest {
    private final ApplicationContextRunner standalone = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NovaOpsStabilizationAutoConfiguration.class))
            .withPropertyValues(
                    "nova.orch.interrupt-hygiene.cancel-shield.enabled=false",
                    "nova.orch.debug.reactor-onErrorDropped.enabled=false",
                    "nova.orch.embedding.matryoshka-shield.enabled=false");

    private final ApplicationContextRunner masterDisabled = standalone
            .withConfiguration(AutoConfigurations.of(NovaOrchestrationAutoConfiguration.class))
            .withPropertyValues("nova.orch.enabled=false");

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @ParameterizedTest(name = "explicitOpsEnabled={0}")
    @ValueSource(booleans = {false, true})
    void masterDisabledKeepsIndependentOpsBackoffAvailable(boolean explicitOpsEnabled) {
        ApplicationContextRunner runner = explicitOpsEnabled
                ? masterDisabled.withPropertyValues("nova.orch.ops.stabilization.enabled=true")
                : masterDisabled;
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(BraveRateLimitState.class);
            assertThat(context).hasSingleBean(RateLimitBackoffCoordinator.class);
            assertThat(context).hasSingleBean(ProviderRateLimitBackoffAspect.class);
            assertThat(context).hasSingleBean(HybridWebSearchEmptyFallbackAspect.class);
        });
    }

    @Test
    void standaloneOpsRetainsNaverRateLimitBehaviorWithoutBraveState() {
        standalone.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(BraveRateLimitState.class);
            assertDoesNotThrow(() -> assertNaverRateLimitBackoff(
                    context.getBean(ProviderRateLimitBackoffAspect.class),
                    context.getBean(RateLimitBackoffCoordinator.class)));
        });
    }

    @Test
    void explicitlyProvidedStateIsReusedWithMasterDisabled() {
        BraveRateLimitState state = new BraveRateLimitState();
        masterDisabled.withBean(BraveRateLimitState.class, () -> state).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BraveRateLimitState.class);
            assertThat(context).hasSingleBean(ProviderRateLimitBackoffAspect.class);
            assertThat(ReflectionTestUtils.getField(context.getBean(ProviderRateLimitBackoffAspect.class),
                    "braveState")).isSameAs(state);
        });
    }

    @Test
    void explicitBackoffDisableStillTakesPrecedence() {
        masterDisabled.withPropertyValues("nova.orch.web.failsoft.ratelimit-backoff.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RateLimitBackoffCoordinator.class);
                    assertThat(context).doesNotHaveBean(ProviderRateLimitBackoffAspect.class);
                    assertThat(context).hasSingleBean(HybridWebSearchEmptyFallbackAspect.class);
                });
    }

    @Test
    void explicitOpsDisableStillTakesPrecedence() {
        masterDisabled.withPropertyValues("nova.orch.ops.stabilization.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RateLimitBackoffCoordinator.class);
                    assertThat(context).doesNotHaveBean(ProviderRateLimitBackoffAspect.class);
                    assertThat(context).doesNotHaveBean(HybridWebSearchEmptyFallbackAspect.class);
                });
    }

    @Test
    void missingWebClientKeepsExistingClassGuard() {
        standalone.withClassLoader(new FilteredClassLoader(WebClient.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RateLimitBackoffCoordinator.class);
            assertThat(context).doesNotHaveBean(ProviderRateLimitBackoffAspect.class);
        });
    }

    private static void assertNaverRateLimitBackoff(ProviderRateLimitBackoffAspect aspect,
                                                   RateLimitBackoffCoordinator coordinator) throws Throwable {
        ProceedingJoinPoint invocation = mock(ProceedingJoinPoint.class);
        doThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)).when(invocation).proceed();
        assertThat(aspect.aroundNaverSearchSnippetsSync(invocation)).isEqualTo(List.of());
        assertThat(coordinator.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER).shouldSkip()).isTrue();
    }
}
