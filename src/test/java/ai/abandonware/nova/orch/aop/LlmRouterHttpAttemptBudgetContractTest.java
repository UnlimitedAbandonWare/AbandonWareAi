package ai.abandonware.nova.orch.aop;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmRouterHttpAttemptBudgetContractTest {

    @Test
    void routedChatModelsDisableLibraryLevelRetries() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);
        int builderStart = source.indexOf("OpenAiChatModel.builder()");
        int builderEnd = source.indexOf(
                "ChatModel routedModel = verifyResponseModelIfRequired(b.build(), modelName, cfg);",
                builderStart);
        assertTrue(builderStart >= 0 && builderEnd > builderStart, "router builder must remain present");

        String builderBlock = source.substring(builderStart, builderEnd);
        assertTrue(builderBlock.contains(
                        ".maxRetries(ca.maxRetriesOverride == null ? 0 : Math.max(0, ca.maxRetriesOverride))"),
                "one logical API role call must not expand into hidden library retries");
    }

    @Test
    void fallbackTransportTimeoutUsesOnlyTheCurrentRequestBudget() throws Exception {
        TimeBudget requestBudget = mock(TimeBudget.class);
        when(requestBudget.remainingMillis()).thenReturn(47L);
        TimeBudgetContext.set(requestBudget);
        try {
            Method timeoutMethod = LlmRouterAspect.class.getDeclaredMethod(
                    "routeTimeoutMillis", long.class, String.class);
            timeoutMethod.setAccessible(true);

            assertEquals(47L, timeoutMethod.invoke(null, 5_000L, "fallback"));
            assertEquals(5_000L, timeoutMethod.invoke(null, 5_000L, "primary"));
        } finally {
            TimeBudgetContext.clear();
        }
    }

    @Test
    void everyFallbackCapableOpenAiClientUsesTheRoleAwareMillisecondTimeout() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("routeTimeoutMillis(Math.max(1_000L, ca.timeoutMs), attemptRole)"),
                "the active route builder must derive one role-aware transport timeout");
        assertTrue(source.contains(
                        "new OpenAiResponsesChatModel(baseUrl, apiKey, modelName, routeTimeoutMs,"),
                "Responses fallback must use the remaining-time cap");
        assertTrue(source.contains("Duration.ofMillis(routeTimeoutMs)"),
                "chat-completions and Gemini fallback clients must use millisecond remaining time");
    }
}
