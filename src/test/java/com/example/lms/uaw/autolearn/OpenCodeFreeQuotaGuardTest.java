package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeFreeQuotaGuardTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void allowsOpenCodeFreeModelAndReservesLocalBudget() {
        OpenCodeFreeQuotaGuard guard = guard(props(), env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));

        OpenCodeFreeQuotaGuard.Decision decision = guard.tryAcquire("llmrouter.external", 512, 0);

        assertTrue(decision.allowed());
        assertTrue(decision.consumed());
        assertNotNull(decision.lease());
        Map<String, Object> status = guard.status();
        assertEquals(1, status.get("callsToday"));
        assertEquals(2, status.get("remainingCalls"));
        assertEquals(512, status.get("outputTokensReservedToday"));
        assertEquals(1024, status.get("remainingOutputTokens"));
        assertEquals(true, status.get("routeEnabled"));
        assertEquals(true, status.get("hasKey"));
        assertEquals(false, status.get("consumed"));
        assertEquals(512, status.get("nextReservationTokens"));
        assertEquals("opencode.ai", status.get("endpointHost"));
        assertEquals("deepseek-v4-flash-free", status.get("model"));
        assertEquals("UTC", status.get("resetZone"));
        assertEquals("STATIC_SYNTHETIC_ONLY", status.get("privacyMode"));
        assertEquals("EXTERNAL_FREE_CURATE_ONLY", status.get("canonicalTrainingPolicy"));
        assertEquals("CHAT_COMPLETIONS", status.get("endpointFamily"));
        assertEquals("CURATE_ONLY", status.get("modelPolicy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceSnapshotStoresHashOnlyModelIdentifiers() {
        String rawModel = "private-free-model-name";
        UawAutolearnProperties props = props();
        props.getExternalQuota().setFreeModel(rawModel);
        OpenCodeFreeQuotaGuard guard = guard(props, env(rawModel, "https://opencode.ai/zen/v1"));

        OpenCodeFreeQuotaGuard.Decision decision = guard.tryAcquire("llmrouter.external", 512, 0);

        assertTrue(decision.allowed());
        Object traceValue = TraceStore.get("uaw.autolearn.externalQuota");
        assertTrue(traceValue instanceof Map<?, ?>);
        Map<String, Object> trace = (Map<String, Object>) traceValue;
        String rendered = String.valueOf(trace);
        assertFalse(rendered.contains(rawModel));
        assertFalse(rendered.contains("llmrouter.external"));
        assertFalse(trace.containsKey("model"));
        assertFalse(trace.containsKey("freeModel"));
        assertFalse(trace.containsKey("routeModel"));
        assertFalse(trace.containsKey("requestedModel"));
        assertEquals(SafeRedactor.hashValue(rawModel), trace.get("modelHash"));
        assertEquals(rawModel.length(), trace.get("modelLength"));
        assertEquals(SafeRedactor.hashValue("llmrouter.external"), trace.get("requestedModelHash"));
        assertEquals("opencode.ai", trace.get("endpointHost"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceSnapshotSanitizesDisabledReasonAtWriteBoundary() throws Exception {
        OpenCodeFreeQuotaGuard guard = guard(props(), env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));
        Method method = OpenCodeFreeQuotaGuard.class.getDeclaredMethod(
                "trace", OpenCodeFreeQuotaGuard.Decision.class, Map.class);
        method.setAccessible(true);
        String rawReason = "private quota reason ownerToken=secret Authorization=Bearer " + "local-placeholder";
        Map<String, Object> status = Map.of(
                "applies", true,
                "routeEnabled", true,
                "hasKey", true,
                "nextReservationTokens", 128,
                "disabledReason", rawReason,
                "routeModel", "llmrouter.external",
                "requestedModel", "llmrouter.external",
                "model", "deepseek-v4-flash-free",
                "freeModel", "deepseek-v4-flash-free");

        method.invoke(guard, OpenCodeFreeQuotaGuard.Decision.denied(rawReason, "deepseek-v4-flash-free"), status);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("Authorization"), trace);
        assertTrue(String.valueOf(TraceStore.get("uaw.autolearn.externalQuota.disabledReason")).contains("hash:"), trace);
        Object snapshot = TraceStore.get("uaw.autolearn.externalQuota");
        assertTrue(snapshot instanceof Map<?, ?>);
        assertTrue(String.valueOf(((Map<String, Object>) snapshot).get("disabledReason")).contains("hash:"), trace);
    }

    @Test
    void defaultExternalQuotaConfigIsObserveOnlyAndDoesNotConsumeBudget() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getBudget().setStatePath(tempDir.resolve("autolearn_state.json").toString());
        OpenCodeFreeQuotaGuard guard = guard(props, env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));

        OpenCodeFreeQuotaGuard.Decision decision = guard.tryAcquire("llmrouter.external", 512, 0);

        assertTrue(decision.allowed());
        assertFalse(decision.consumed());
        assertEquals("disabled_by_config", decision.disabledReason());
        assertEquals(false, guard.status().get("enabled"));
    }

    @Test
    void deniesDisabledRouteBeforeChatServiceCanCallProvider() {
        MockEnvironment env = env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1");
        env.setProperty("llmrouter.models.external.enabled", "false");
        OpenCodeFreeQuotaGuard guard = guard(props(), env);

        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 512, 0);

        assertFalse(denied.allowed());
        assertEquals("route_disabled", denied.disabledReason());
        Map<String, Object> status = guard.status();
        assertEquals(false, status.get("routeEnabled"));
        assertEquals(true, status.get("hasKey"));
        assertEquals("route_disabled", status.get("disabledReason"));
    }

    @Test
    void deniesMissingOpenCodeKeyBeforeChatServiceCanCallProvider() {
        OpenCodeFreeQuotaGuard guard = guard(props(),
                env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1", null));

        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 512, 0);

        assertFalse(denied.allowed());
        assertEquals("missing_opencode_api_key", denied.disabledReason());
        Map<String, Object> status = guard.status();
        assertEquals(false, status.get("hasKey"));
        assertEquals("missing_opencode_api_key", status.get("disabledReason"));
    }

    @Test
    void deniesPlaceholderOpenCodeKeyBeforeChatServiceCanCallProvider() {
        OpenCodeFreeQuotaGuard guard = guard(props(),
                env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1", "dummy"));

        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 512, 0);

        assertFalse(denied.allowed());
        assertEquals("missing_opencode_api_key", denied.disabledReason());
    }

    @Test
    void deniesConflictingOpenCodeKeySourcesBeforeChatServiceCanCallProvider() {
        MockEnvironment env = env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1", "opencode-env-key");
        env.setProperty("llm.opencode.api-key", "opencode-property-key");
        OpenCodeFreeQuotaGuard guard = guard(props(), env);

        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 512, 0);

        assertFalse(denied.allowed());
        assertEquals("opencode_api_key_conflict", denied.disabledReason());
        Map<String, Object> status = guard.status();
        assertEquals(false, status.get("hasKey"));
        assertEquals("opencode_api_key_conflict", status.get("disabledReason"));
    }

    @Test
    void deniesWhenDailyCallBudgetIsExhausted() {
        UawAutolearnProperties props = props();
        props.getExternalQuota().setMaxCallsPerDay(1);
        OpenCodeFreeQuotaGuard guard = guard(props, env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));

        assertTrue(guard.tryAcquire("llmrouter.external", 512, 0).allowed());
        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 512, 0);

        assertFalse(denied.allowed());
        assertEquals("daily_call_limit", denied.disabledReason());
    }

    @Test
    void deniesNonFreeModelWhenStrictFreeModelOnlyIsEnabled() {
        OpenCodeFreeQuotaGuard guard = guard(props(), env("deepseek-paid", "https://opencode.ai/zen/v1"));

        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 256, 0);

        assertFalse(denied.allowed());
        assertEquals("non_free_model", denied.disabledReason());
    }

    @Test
    void deniesNonOpenCodeHost() {
        OpenCodeFreeQuotaGuard guard = guard(props(), env("deepseek-v4-flash-free", "https://api.openai.com/v1"));

        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 256, 0);

        assertFalse(denied.allowed());
        assertEquals("provider_host_mismatch", denied.disabledReason());
    }

    @Test
    void deniesHostThatOnlyContainsOpenCodeName() {
        OpenCodeFreeQuotaGuard guard = guard(props(), env("deepseek-v4-flash-free", "https://evilopencode.ai/zen/v1"));

        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 256, 0);

        assertFalse(denied.allowed());
        assertEquals("provider_host_mismatch", denied.disabledReason());
    }

    @Test
    void statusUsesStrictMaxTokensAfterPerCallClampForNextReservation() {
        MockEnvironment env = env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1")
                .withProperty("uaw.autolearn.strict.max-tokens", "2048");
        OpenCodeFreeQuotaGuard guard = guard(props(), env);

        Map<String, Object> status = guard.status();

        assertEquals(512, status.get("nextReservationTokens"));
        assertEquals(512, status.get("maxOutputTokensPerCall"));
        assertEquals(true, status.get("allowed"));
    }

    @Test
    void latchesRateLimitAfterProviderQuotaFailure() {
        OpenCodeFreeQuotaGuard guard = guard(props(), env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));
        OpenCodeFreeQuotaGuard.Decision first = guard.tryAcquire("llmrouter.external", 256, 0);

        guard.recordFailure(first.lease(), new RuntimeException("HTTP 429 rate limit"));
        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 256, 0);

        assertFalse(denied.allowed());
        assertEquals("rate_limit_cooldown", denied.disabledReason());
        Map<String, Object> status = guard.status();
        assertEquals("rate_limit_cooldown", status.get("disabledReason"));
    }

    @Test
    void deniesWhenQuotaStateCannotBePersisted() {
        AutoLearnRunStateStore failingStore = new AutoLearnRunStateStore() {
            @Override
            public boolean saveStrict(Path path, AutoLearnRunState state) {
                return false;
            }
        };
        OpenCodeFreeQuotaGuard guard = new OpenCodeFreeQuotaGuard(
                props(),
                failingStore,
                env("deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));

        OpenCodeFreeQuotaGuard.Decision denied = guard.tryAcquire("llmrouter.external", 256, 0);

        assertFalse(denied.allowed());
        assertEquals("quota_state_persist_failed", denied.disabledReason());
    }

    @Test
    void openCodeFreeQuotaGuardDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/OpenCodeFreeQuotaGuard.java"));

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "OpenCode quota trace paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("TRACE_PREFIX + \"suppressed.intProp\""));
        assertTrue(source.contains("TraceStore.put(TRACE_PREFIX + \"suppressed.keyStatus\", \"opencode_api_key_conflict\")"));
        assertTrue(source.contains("[AWX][uaw][quota] invalid reset zone; using UTC"));
    }

    private OpenCodeFreeQuotaGuard guard(UawAutolearnProperties props, MockEnvironment env) {
        return new OpenCodeFreeQuotaGuard(props, new AutoLearnRunStateStore(), env);
    }

    private UawAutolearnProperties props() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getBudget().setStatePath(tempDir.resolve("autolearn_state.json").toString());
        props.getExternalQuota().setMaxCallsPerDay(3);
        props.getExternalQuota().setMaxOutputTokensPerDay(1536);
        props.getExternalQuota().setMaxOutputTokensPerCall(512);
        props.getExternalQuota().setMaxCallsPerCycle(1);
        props.getExternalQuota().setRateLimitCooldownSeconds(60);
        props.getExternalQuota().setEnabled(true);
        return props;
    }

    private static MockEnvironment env(String routeModel, String baseUrl) {
        return env(routeModel, baseUrl, "must-not-be-read");
    }

    private static MockEnvironment env(String routeModel, String baseUrl, String openCodeApiKey) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("uaw.autolearn.strict.model", "llmrouter.external")
                .withProperty("llmrouter.models.external.enabled", "true")
                .withProperty("llmrouter.models.external.name", routeModel)
                .withProperty("llmrouter.models.external.base-url", baseUrl);
        if (openCodeApiKey != null) {
            env.withProperty("OPENCODE_API_KEY", openCodeApiKey);
        }
        return env;
    }
}
