package ai.abandonware.nova.orch.llm;

import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.aop.OpenAiChatModelGuardAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelGuardEndpointContractTest {
    private NovaModelGuardProperties configured() throws Exception {
        MockEnvironment env = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("llm",
                new FileSystemResource("main/resources/application-llm.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        env.setProperty("llm.chat-model", "fixture-chat");
        ConfigurationPropertySources.attach(env);
        return Binder.get(env).bind("nova.orch.model-guard", NovaModelGuardProperties.class).get();
    }

    @Test
    void yamlAndJavaDefaultsPreserveChatModelsAndProtectExclusiveModels() throws Exception {
        for (var props : List.of(new NovaModelGuardProperties(), configured())) {
            for (String model : List.of("gpt-4.1", "gpt-4.1-mini", "gpt-4o", "gpt-4o-mini",
                    "o3", "o3-mini", "o4-mini", "fixture-unknown")) {
                assertFalse(ModelGuardSupport.isResponsesOnlyModel(model, props.getResponsesOnlyPrefixes()), model);
            }
            for (String model : List.of("gpt-5-pro", "o3-deep-research", "o4-mini-deep-research")) {
                assertTrue(ModelGuardSupport.isResponsesOnlyModel(model, props.getResponsesOnlyPrefixes()), model);
            }
        }
    }

    @Test
    void configuredNamesDoNotImplyUnverifiedVariants() {
        assertFalse(ModelGuardSupport.isResponsesOnlyModel("gpt-5-pro-unverified", List.of("gpt-5-pro")));
        assertTrue(ModelGuardSupport.isResponsesOnlyModel("fixture-2026-01-01", List.of("fixture-2026-01-01")));
        assertFalse(ModelGuardSupport.isResponsesOnlyModel("fixture-2026-01-02", List.of("fixture-2026-01-01")));
    }

    @Test
    void officialHostMustBeAnExactHttpsAuthority() {
        assertTrue(ModelGuardSupport.looksLikeOpenAiBaseUrl("HTTPS://API.OPENAI.COM/v1"));
        for (String url : List.of("https://api.openai.com.evil.invalid/v1",
                "https://evil.invalid/api.openai.com/v1", "https://api.openai.com@evil.invalid/v1",
                "http://api.openai.com/v1", "https://evilopenai.com/v1", "http://localhost:11434/v1")) {
            assertFalse(ModelGuardSupport.looksLikeOpenAiBaseUrl(url), url);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void yamlGuardProceedsOnceWithoutChangingChatModel() throws Throwable {
        var props = configured();
        var aspect = new OpenAiChatModelGuardAspect(props,
                new MockEnvironment().withProperty("llm.base-url", "https://api.openai.com/v1"),
                mock(ObjectProvider.class));
        var pjp = mock(ProceedingJoinPoint.class);
        Object expected = new Object();
        when(pjp.getArgs()).thenReturn(new Object[]{"gpt-4.1", 1.0, 1.0, 256, 30});
        when(pjp.proceed()).thenReturn(expected);
        assertSame(expected, aspect.guardLcWithTimeout(pjp));
        verify(pjp, times(1)).proceed();
        verify(pjp, never()).proceed(any(Object[].class));
    }
}
