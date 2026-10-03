package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.*;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.*;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import dev.langchain4j.model.chat.ChatModel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatGptRoutingBindingContractTest {
    private static final String ROUTE = "chatgpt-oauth:fixture-gpt";

    @Test void explicitOauthBypassesBanditAndApiFallbackEvenOnFailure() throws Throwable {
        var props = new LlmRouterProperties(); props.setEnabled(true);
        var bandit = mock(LlmRouterBandit.class);
        var probe = mock(HybridLlmGatewayProbeService.class);
        var aspect = new LlmRouterAspect(new MockEnvironment(), props, bandit,
                new NovaModelGuardProperties(), null, probe, null, null);
        var join = mock(ProceedingJoinPoint.class);
        when(join.getArgs()).thenReturn(new Object[]{ROUTE});
        var failure = new IllegalStateException("synthetic OAuth unavailable");
        when(join.proceed()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> aspect.aroundLcWithTimeout(join)));
        verify(join, times(1)).proceed();
        verifyNoInteractions(bandit, probe);
    }

    @Test void factoryPreparedAndDirectCallsUseIdenticalBindingWithoutApiKey() {
        var key = mock(KeyResolver.class);
        var factory = new DynamicChatModelFactory(new MockEnvironment(), key);
        var registration = mock(ChatGptOAuthRegistration.class);
        var selected = mock(ChatModel.class);
        when(registration.available(ROUTE)).thenReturn(true);
        when(registration.modelFor(ROUTE, 2000L)).thenReturn(selected);
        ReflectionTestUtils.setField(factory, "chatGptOAuth", registration);
        assertTrue(factory.canServe(ROUTE));
        assertSame(selected, factory.lcWithTimeout(ROUTE, .4, .9, null, null, 99, 2, 7));
        assertSame(selected, factory.lcForPreparedAnswer(ROUTE, .4, .9, null, null, 99, 2));
        verify(registration, times(2)).modelFor(ROUTE, 2000L);
        verifyNoInteractions(key);
    }

    @Test void cueApiAttemptUsesOauthWithoutConfiguredApiRouteOrCredentials() {
        var props = new LlmRouterProperties(); props.setEnabled(true);
        var probe = mock(HybridLlmGatewayProbeService.class);
        var aspect = new LlmRouterAspect(new MockEnvironment(), props, mock(LlmRouterBandit.class),
                new NovaModelGuardProperties(), null, probe, null, null);
        var registration = mock(ChatGptOAuthRegistration.class);
        var selected = mock(ChatModel.class);
        when(registration.modelFor(ROUTE, 2500L)).thenReturn(selected);
        ReflectionTestUtils.setField(aspect, "chatGptOAuth", registration);
        assertSame(selected, aspect.apiAttempt(ROUTE, 2500, 77));
        verifyNoInteractions(probe);
        var unavailable = new DynamicChatModelFactory(new MockEnvironment(), mock(KeyResolver.class));
        assertFalse(unavailable.canServe(ROUTE));
        assertThrows(RuntimeException.class, () -> unavailable.lcWithTimeout(ROUTE, null, null, 32, 2));
    }
}
