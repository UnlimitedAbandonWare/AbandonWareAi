package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRuntimeHealthTrackerTest {
    @Test void directReceiptAllowsOnlyOneOfficialAttemptAndRetainsOnlyAllowedMetadata(){
        try(var receipt=ModelRuntimeHealthTracker.beginDirectOpenAiReceipt()){
            receipt.started("https://api.openai.com/v1/chat/completions");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->receipt.started("https://api.openai.com/v1/chat/completions"));
            receipt.received(200,Map.of("X-Request-ID",java.util.List.of("req_fixture"),"openai-organization",java.util.List.of("org-fixture"),"Authorization",java.util.List.of("synthetic-private")));
            var result=receipt.snapshot();assertEquals(1,result.get("httpAttemptCount"));assertEquals("req_fixture",result.get("providerRequestId"));
            assertTrue(result.containsKey("openai-organizationHash"));assertFalse(result.toString().contains("org-fixture"));assertFalse(result.toString().contains("synthetic-private"));
        }
        try(var receipt=ModelRuntimeHealthTracker.beginDirectOpenAiReceipt()){
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->receipt.started("http://127.0.0.1/v1/chat/completions"));
            receipt.failed(new dev.langchain4j.exception.HttpException(401,"{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"synthetic-private\"}}"));
            assertEquals(0,receipt.snapshot().get("httpAttemptCount"));assertEquals(401,receipt.snapshot().get("httpStatus"));assertEquals("invalid_api_key",receipt.snapshot().get("error_code"));
            assertFalse(receipt.snapshot().toString().contains("synthetic-private"));
        }
    }

    @Test
    void endpointHealthIsIsolatedInBothSuccessFailureOrders() {
        ModelRuntimeHealthTracker first = new ModelRuntimeHealthTracker();
        SelectedRoute endpointA = selectedRoute(
                first, "local", "http://127.0.0.1:11434/v1/chat/completions",
                "qwen3:8b", "native-route", "ollama_native", false);
        SelectedRoute endpointB = selectedRoute(
                first, "local", "http://127.0.0.1:11435/v1/chat/completions",
                "qwen3:8b", "native-route", "ollama_native", false);
        assertNotNull(endpointA);
        assertNotNull(endpointB);
        assertNotEquals(endpointA.key(), endpointB.key());

        promote(first, endpointA, acceptedWithoutVerifier());
        first.recordRouteFailure(endpointB.key(), "timeout_soft");

        assertTrue(first.isPromotable(endpointA.key()));
        assertFalse(first.isPromotable(endpointB.key()));
        assertTrue(first.snapshot(endpointA.key()).orElseThrow().lastSuccess());
        assertFalse(first.snapshot(endpointB.key()).orElseThrow().lastSuccess());

        ModelRuntimeHealthTracker second = new ModelRuntimeHealthTracker();
        SelectedRoute secondA = selectedRoute(
                second, "local", "http://127.0.0.1:11434/v1/chat/completions",
                "qwen3:8b", "native-route", "ollama_native", false);
        SelectedRoute secondB = selectedRoute(
                second, "local", "http://127.0.0.1:11435/v1/chat/completions",
                "qwen3:8b", "native-route", "ollama_native", false);

        second.recordRouteFailure(secondB.key(), "timeout_soft");
        promote(second, secondA, acceptedWithoutVerifier());

        assertTrue(second.isPromotable(secondA.key()));
        assertFalse(second.isPromotable(secondB.key()));
    }

    @Test
    void modelAndBoundedRuntimeContextHealthRemainIsolated() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String endpoint = "https://models.example.test:443/v1/chat/completions";
        SelectedRoute modelA = selectedRoute(
                tracker, "openai", endpoint, "model-a", "route-a",
                "openai_chat_completions", true);
        SelectedRoute modelB = selectedRoute(
                tracker, "openai", endpoint, "model-b", "route-a",
                "openai_chat_completions", true);
        SelectedRoute contextB = selectedRoute(
                tracker, "openai", endpoint, "model-a", "route-b",
                "openai_chat_completions", true);

        promote(tracker, modelA, acceptedWithVerifier());
        tracker.recordRouteFailure(modelB.key(), "upstream_5xx");
        tracker.recordRouteFailure(contextB.key(), "upstream_5xx");

        assertTrue(tracker.isPromotable(modelA.key()));
        assertFalse(tracker.isPromotable(modelB.key()));
        assertFalse(tracker.isPromotable(contextB.key()));
    }

    @Test
    void transportSuccessAndRejectedSemanticOutcomesNeverPromoteHealth() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ModelRuntimeHealthTracker.SemanticOutcome[] rejected = {
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        false,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false, true, true, true),
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.CANCELLED,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false, true, true, true),
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.REQUIRED,
                        false, true, true, true),
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false, false, true, true),
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false, true, false, true),
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.DELIVERY_PENDING,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false, true, true, false),
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        null,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false, true, true, true),
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        null,
                        false, true, true, true),
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.FAILED,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false, true, true, true),
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false, true, true, false)
        };

        AtomicLong lastLocalSuccess = localSuccessClock();
        long previous = lastLocalSuccess.getAndSet(0L);
        try {
            for (int i = 0; i < rejected.length; i++) {
                SelectedRoute route = selectedRoute(
                        tracker,
                        "local",
                        "https://semantic-" + i + ".example.test/v1/responses",
                        "model-" + i,
                        "semantic-route-" + i,
                        "openai_responses",
                        false);
                tracker.recordAttemptSuccess(route.key());
                assertTrue(tracker.snapshot(route.key()).isEmpty(), "transport success must stay attempt-only");
                assertFalse(ModelRuntimeHealthTracker.hasRecentLocalSuccess(Duration.ofSeconds(5)));

                promote(tracker, route, rejected[i]);

                assertFalse(tracker.isPromotable(route.key()), "rejected semantic outcome index=" + i);
                assertTrue(tracker.snapshot(route.key()).isEmpty(), "rejected semantic outcome must not mutate health");
                assertFalse(ModelRuntimeHealthTracker.hasRecentLocalSuccess(Duration.ofSeconds(5)));
            }

            SelectedRoute accepted = selectedRoute(
                    tracker,
                    "local",
                    "https://accepted.example.test/v1/responses",
                    "accepted-model",
                    "accepted-route",
                    "openai_responses",
                    true);
            tracker.recordAttemptSuccess(accepted.key());
            promote(tracker, accepted, acceptedWithVerifier());

            assertTrue(tracker.isPromotable(accepted.key()));
            assertEquals(1L, tracker.snapshot(accepted.key()).orElseThrow().successCount());
            assertTrue(ModelRuntimeHealthTracker.hasRecentLocalSuccess(Duration.ofSeconds(5)));
        } finally {
            lastLocalSuccess.set(previous);
        }
    }

    @Test
    void routeSnapshotsRetainOnlyHashAndBoundedLabels() {
        String rawProviderSecret = "openai-sk-"" + ""modelHealthSecretabcdefghijklmnopqrstuvwxyz";
        String rawModelSecret = "model-sb_secret_modelhealth123456";
        String rawEndpoint = "https://user:private-password@private.example.test:8443/v1/responses"
                + "?api_key=sk-"" + ""endpointSecretabcdefghijklmnopqrstuvwxyz#private-fragment";
        String rawContext = "RAW_PRIVATE_CONTEXT_81 owner-token=private-token";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        SelectedRoute selected = selectedRoute(
                tracker,
                rawProviderSecret,
                rawEndpoint,
                rawModelSecret,
                rawContext,
                "openai_responses",
                true);
        ModelRuntimeHealthTracker.RouteHealthKey route = selected.key();
        assertNotNull(route);

        promote(tracker, selected, acceptedWithVerifier());

        String publicText = String.valueOf(tracker.redactedSnapshots());
        String keyText = String.valueOf(route);
        for (String raw : new String[] {
                rawProviderSecret, rawModelSecret, "private-password", "api_key", "endpointSecret",
                "private-fragment", rawContext, "private-token"}) {
            assertFalse(publicText.contains(raw), publicText);
            assertFalse(keyText.contains(raw), keyText);
        }
        assertTrue(route.endpointHash().matches("hash:[0-9a-f]{12}"), route.endpointHash());
        assertTrue(route.context().matches(
                "router:openai_responses:hash:[0-9a-f]{12}"), route.context());
    }

    @Test
    void seedLocalChatModelsCoverInstalledRoleDefaultsOnly() {
        assertTrue(ModelRuntimeHealthTracker.isSeedLocalChatModel("gemma4:26b"));
        assertTrue(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3.5:9b"));
        assertTrue(ModelRuntimeHealthTracker.isSeedLocalChatModel("smtek/Qwen3.8-27B:Q3_K_XL"));
        assertTrue(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3-vl:8b"));

        assertFalse(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3:8b"));
        assertFalse(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3:30b"));
        assertFalse(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3-coder:30b"));
        assertFalse(ModelRuntimeHealthTracker.isSeedLocalChatModel("gemma4:31b-it-q4_K_M"));
        assertFalse(ModelRuntimeHealthTracker.isSeedLocalChatModel("qwen3-embedding:4b"));
    }

    @Test
    void missingModelFailureBlocksPromotionUntilSuccess() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "gemma4:31b-it-q4_K_M",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "not_installed");

        assertFalse(tracker.isPromotable("local", "gemma4:31b-it-q4_K_M"));

        tracker.recordSuccess("local", "gemma4:31b-it-q4_K_M",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        assertTrue(tracker.isPromotable("local", "gemma4:31b-it-q4_K_M"));
    }

    @Test
    void upstream5xxBlocksSeedLocalPromotionUntilSuccess() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "gemma4:26b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "upstream_5xx");

        assertFalse(tracker.isPromotable("local", "gemma4:26b"));

        tracker.recordSuccess("local", "gemma4:26b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        assertTrue(tracker.isPromotable("local", "gemma4:26b"));
    }

    @Test
    void blankResponseBlocksSeedLocalPromotionUntilSuccess() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "blank_response");

        assertFalse(tracker.isPromotable("local", "qwen3:8b"));

        tracker.recordSuccess("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        assertTrue(tracker.isPromotable("local", "qwen3:8b"));
    }

    @Test
    void publicSnapshotExposesBoundedFailurePressure() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "blank_response");
        tracker.recordFailure("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "upstream_5xx");
        tracker.recordSuccess("local", "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        Map<String, Object> snapshot = tracker.redactedSnapshot("local", "qwen3:8b");

        assertEquals(3L, snapshot.get("sampleCount"));
        double failurePressure = ((Number) snapshot.get("failurePressure")).doubleValue();
        assertTrue(failurePressure > 0.0d && failurePressure < 1.0d);
        assertEquals("llm_route_degrade", snapshot.get("routingHint"));
    }

    @Test
    void embeddingModelsAreNeverPromotableForChat() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordSuccess("local", "qwen3-embedding:4b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        assertFalse(tracker.isPromotable("local", "qwen3-embedding:4b"));
    }

    @Test
    void failureReasonDoesNotExposeRawSecretsInPublicSnapshot() {
        String rawKey = "sk-" + "modelHealthSecretabcdefghijklmnopqrstuvwxyz";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();

        tracker.recordFailure("openai", "gpt-5.5-pro",
                OpenAiEndpointCompatibility.Endpoint.RESPONSES,
                "Authorization Bearer " + rawKey + " endpoint_mismatch");

        Map<String, Object> snapshot = tracker.redactedSnapshot("openai", "gpt-5.5-pro");
        String publicText = String.valueOf(snapshot);

        assertFalse(publicText.contains(rawKey));
        assertFalse(publicText.contains("Bearer " + rawKey));
    }

    @Test
    void publicSnapshotRedactsSecretShapedProviderAndModelLabels() {
        String rawKey = "sb_secret_" + "modelhealth123456";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();

        tracker.recordFailure("local-" + rawKey, "gemma4:26b-" + rawKey,
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                "http_404");

        String publicText = String.valueOf(tracker.redactedSnapshots());

        assertFalse(publicText.contains(rawKey), publicText);
        assertFalse(publicText.contains("sb_secret_"), publicText);
    }

    @Test
    void freeFormEndpointMismatchReasonKeepsBlockingLabelWithoutRawText() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "gemma4:26b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                "Responses endpoint mismatch for private prompt with owner token");

        Map<String, Object> snapshot = tracker.redactedSnapshot("local", "gemma4:26b");
        String lastReason = String.valueOf(snapshot.get("lastReason"));

        assertFalse(tracker.isPromotable("local", "gemma4:26b"));
        assertEquals("endpoint_mismatch", lastReason);
        assertFalse(lastReason.contains("private prompt"));
        assertFalse(lastReason.contains("owner token"));
    }

    @Test
    void reasonSanitizerUsesTraceLabelsInsteadOfSafeMessageText() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String redacted = SafeRedactor.safeMessage(reason, 120);"));
        assertTrue(source.contains("String label = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
    }

    private static ModelRuntimeHealthTracker.SemanticOutcome acceptedWithoutVerifier() {
        return new ModelRuntimeHealthTracker.SemanticOutcome(
                true,
                ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                false,
                true,
                true,
                true);
    }

    private static ModelRuntimeHealthTracker.SemanticOutcome acceptedWithVerifier() {
        return new ModelRuntimeHealthTracker.SemanticOutcome(
                true,
                ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                ModelRuntimeHealthTracker.VerificationPolicy.REQUIRED,
                true,
                true,
                true,
                true);
    }

    private static SelectedRoute selectedRoute(
            ModelRuntimeHealthTracker tracker,
            String provider,
            String endpoint,
            String model,
            String routeKey,
            String protocol,
            boolean responseModelVerificationRequired) {
        String timelineId = tracker.beginRequestTimeline(
                "request-" + routeKey + '-' + model,
                "session-" + routeKey + '-' + model);
        tracker.recordRequestPhase(timelineId, "dispatch", model, null, "none");
        tracker.recordRequestPhase(timelineId, "pending", model, endpoint, "none");
        tracker.recordRequestSelection(
                timelineId,
                "router",
                provider,
                routeKey,
                model,
                endpoint,
                protocol,
                responseModelVerificationRequired,
                false);
        return new SelectedRoute(
                timelineId,
                model,
                tracker.requestRouteHealthKey(timelineId).orElseThrow());
    }

    private static boolean promote(
            ModelRuntimeHealthTracker tracker,
            SelectedRoute route,
            ModelRuntimeHealthTracker.SemanticOutcome outcome) {
        return tracker.recordSemanticOutcome(route.timelineId(), route.model(), outcome);
    }

    private record SelectedRoute(
            String timelineId,
            String model,
            ModelRuntimeHealthTracker.RouteHealthKey key) {
    }

    private static AtomicLong localSuccessClock() {
        try {
            java.lang.reflect.Field field = ModelRuntimeHealthTracker.class
                    .getDeclaredField("LAST_LOCAL_SUCCESS_EPOCH_MS");
            field.setAccessible(true);
            return (AtomicLong) field.get(null);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
