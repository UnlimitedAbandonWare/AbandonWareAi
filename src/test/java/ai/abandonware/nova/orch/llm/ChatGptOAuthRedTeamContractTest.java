package ai.abandonware.nova.orch.llm;

import com.example.lms.debug.PromptMasker;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmResponseTerminalException;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Clean-owned red-team contract for the ChatGPT OAuth patch
 * (PASTE_CLEAN_CHATGPT_OAUTH_ASSIST_RAILS_20260930, WP2).
 *
 * FIXED tests guard invariants that must hold today and after the Codex seam
 * lands; they never reference the future OAuth constructor directly. GATE
 * tests unlock automatically via reflection once Codex ships the
 * {@code OpenAiResponsesChatModel(String baseUrl, String model, long timeout,
 * Supplier<String> bearer)} transport seam from PASTE_CODEX_WP2. Bare-JWT and
 * rt_ redaction are active regression contracts.
 *
 * All fixtures are loopback-only; no external call is ever made.
 */
class ChatGptOAuthRedTeamContractTest {

    @org.junit.jupiter.api.io.TempDir Path registrationTemp;

    private com.example.lms.llm.ChatGptOAuthRegistration registration(String scope, long remaining,
            com.example.lms.llm.ChatGptOAuthRegistration.TokenRefresh refresh) throws Exception {
        var credentials = JSON.createObjectNode();
        credentials.put("schemaVersion", "awx.chatgpt-oauth.v1");
        credentials.put("token_type", "Bearer");
        credentials.put("access_token", OAUTH_TOKEN);
        credentials.put("refresh_token", "synthetic-refresh-value");
        credentials.put("expires_at", java.time.Instant.now().getEpochSecond() + remaining);
        if (scope != null) credentials.put("scope", scope);
        Path credentialFile = registrationTemp.resolve("credentials.json");
        Path catalogFile = registrationTemp.resolve("models.json");
        JSON.writeValue(credentialFile.toFile(), credentials);
        JSON.writeValue(catalogFile.toFile(), java.util.Map.of("schemaVersion", "awx.chatgpt-oauth-models.v1",
                "status", "synced", "models", List.of("fixture-model")));
        return new com.example.lms.llm.ChatGptOAuthRegistration(true, credentialFile, catalogFile,
                300, java.time.Clock.systemUTC(), refresh);
    }

    @Test void registrationZeroBudgetIsTimeoutWithoutRefresh() throws Exception {
        var refreshes = new AtomicInteger();
        var reg = registration("chatgpt.tokens.use.direct", 3600, ms -> { refreshes.incrementAndGet(); return false; });
        var terminal = assertThrows(LlmResponseTerminalException.class,
                () -> reg.modelFor("chatgpt-oauth:fixture-model", 0));
        assertEquals(LlmFailureClass.TIMEOUT_SOFT, terminal.failureClass());
        assertEquals("chatgpt_oauth_deadline_exhausted", terminal.reasonCode());
        assertEquals(0, refreshes.get());
    }

    @Test void registrationExpiredZeroBudgetIsTimeoutWithoutRefresh() throws Exception {
        var refreshes = new AtomicInteger();
        var reg = registration("chatgpt.tokens.use.direct", -1, ms -> { refreshes.incrementAndGet(); return false; });
        var terminal = assertThrows(LlmResponseTerminalException.class,
                () -> reg.accessToken("chatgpt-oauth:fixture-model", 0));
        assertEquals(LlmFailureClass.TIMEOUT_SOFT, terminal.failureClass());
        assertEquals("chatgpt_oauth_deadline_exhausted", terminal.reasonCode());
        assertEquals(0, refreshes.get());
    }

    @Test void registrationRequiresExactGrantedDirectScopeForCatalogAndDispatch() throws Exception {
        for (String scope : java.util.Arrays.asList(null, "", "openid profile email",
                "chatgpt.tokens.use.direct.extra", "xchatgpt.tokens.use.direct")) {
            var refreshes = new AtomicInteger();
            var reg = registration(scope, 3600, ms -> { refreshes.incrementAndGet(); return false; });
            assertTrue(reg.models().isEmpty(), "ungranted scope must not be selectable");
            var terminal = assertThrows(LlmResponseTerminalException.class,
                    () -> reg.modelFor("chatgpt-oauth:fixture-model", 1000));
            assertEquals("chatgpt_oauth_scope_missing", terminal.reasonCode());
            assertEquals(LlmFailureClass.AUTH_MISSING, terminal.failureClass());
            assertThrows(LlmResponseTerminalException.class,
                    () -> reg.accessToken("chatgpt-oauth:fixture-model", 1000));
            assertFalse(terminal.toString().contains(OAUTH_TOKEN));
            assertEquals(0, refreshes.get());
        }
    }

    @Test void registrationGrantedDirectScopeAllowsMetadataAndDispatchWithoutRefresh() throws Exception {
        var reg = registration("openid\tchatgpt.tokens.use.direct email", 3600, ms -> { fail("no refresh"); return false; });
        assertEquals(List.of("fixture-model"), reg.models());
        assertNotNull(reg.modelFor("chatgpt-oauth:fixture-model", 1000));
        assertEquals(OAUTH_TOKEN, reg.accessToken("chatgpt-oauth:fixture-model", 1000));
    }

    @Test void registrationCancelledBeforeRefreshDoesNotStartIt() throws Exception {
        var refreshes = new AtomicInteger();
        var reg = registration("chatgpt.tokens.use.direct", -1, ms -> { refreshes.incrementAndGet(); return false; });
        Thread.currentThread().interrupt();
        try {
            var terminal = assertThrows(LlmResponseTerminalException.class,
                    () -> reg.accessToken("chatgpt-oauth:fixture-model", 1000));
            assertEquals(LlmFailureClass.CANCELLED_NEUTRAL, terminal.failureClass());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, refreshes.get());
        } finally { Thread.interrupted(); }
    }

    @Test void registrationInterruptedRefreshRemainsCancellation() throws Exception {
        var reg = registration("chatgpt.tokens.use.direct", -1, ms -> { throw new InterruptedException("synthetic"); });
        try {
            var terminal = assertThrows(LlmResponseTerminalException.class,
                    () -> reg.accessToken("chatgpt-oauth:fixture-model", 1000));
            assertEquals(LlmFailureClass.CANCELLED_NEUTRAL, terminal.failureClass());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void registrationRefreshDeadlineRemainsTimeout() throws Exception {
        var reg = registration("chatgpt.tokens.use.direct", -1,
                ms -> { throw new java.util.concurrent.TimeoutException("synthetic"); });
        var terminal = assertThrows(LlmResponseTerminalException.class,
                () -> reg.accessToken("chatgpt-oauth:fixture-model", 1000));
        assertEquals(LlmFailureClass.TIMEOUT_SOFT, terminal.failureClass());
        assertEquals("chatgpt_oauth_deadline_exhausted", terminal.reasonCode());
    }

    @Test void registrationRefreshFalseWithInterruptRemainsCancellation() throws Exception {
        var reg = registration("chatgpt.tokens.use.direct", -1,
                ms -> { Thread.currentThread().interrupt(); return false; });
        try {
            var terminal = assertThrows(LlmResponseTerminalException.class,
                    () -> reg.accessToken("chatgpt-oauth:fixture-model", 1000));
            assertEquals(LlmFailureClass.CANCELLED_NEUTRAL, terminal.failureClass());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    /** Keys the OAuth Responses path must never transmit (probe-verified 400s). */
    private static final Set<String> FORBIDDEN_WIRE_KEYS = Set.of(
            "temperature", "top_p", "max_output_tokens", "background", "previous_response_id");

    /** Dynamic fixture tokens: the test source itself never embeds a secret-shaped literal. */
    private static final String OAUTH_TOKEN = "synthetic-oauth-" + "t".repeat(8);

    static final ObjectMapper JSON = new ObjectMapper();

    static String sseEvent(String type, String fields) {
        return "event: " + type + "\ndata: {\"type\":\"" + type + "\"," + fields + "}\n\n";
    }

    static final String SSE_COMPLETED =
            sseEvent("response.output_text.delta", "\"delta\":\"ok\"")
            + sseEvent("response.completed",
                    "\"response\":{\"id\":\"resp_rt\",\"status\":\"completed\",\"model\":\"fixture-model\","
                    + "\"usage\":{\"input_tokens\":2,\"output_tokens\":1,\"total_tokens\":3}}");

    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<JsonNode> body = new AtomicReference<>();
        final AtomicReference<String> auth = new AtomicReference<>();
        final AtomicReference<String> accept = new AtomicReference<>();

        Fixture(String response, int status, String contentType) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", e -> {
                calls.incrementAndGet();
                body.set(JSON.readTree(e.getRequestBody()));
                auth.set(e.getRequestHeaders().getFirst("Authorization"));
                accept.set(e.getRequestHeaders().getFirst("Accept"));
                byte[] out = response.getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type", contentType);
                e.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) e.getResponseBody().write(out);
                e.close();
            });
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        OpenAiResponsesChatModel apiKeyModel(String key) {
            return new OpenAiResponsesChatModel(base(), key, "fixture-model", 3000);
        }
        OpenAiResponsesChatModel oauthModel(Supplier<String> bearer) throws Exception {
            return OpenAiResponsesChatModel.class
                    .getConstructor(String.class, String.class, long.class, Supplier.class)
                    .newInstance(base(), "fixture-model", 3000L, bearer);
        }
        public void close() { server.stop(0); }
    }

    static boolean oauthTransportSeamPresent() {
        try {
            OpenAiResponsesChatModel.class.getConstructor(
                    String.class, String.class, long.class, Supplier.class);
            return true;
        } catch (ReflectiveOperationException missing) {
            return false;
        }
    }

    static void assumeOauthSeam() {
        assumeTrue(oauthTransportSeamPresent(),
                "GATE-LOCKED: unlocks when Codex WP2 ships the Supplier<String> bearer constructor");
    }

    // ---------- FIXED (pass today, keep passing after the patch) ----------

    /** A request with no credential must fail closed before any HTTP call. */
    @Test void missingCredentialNeverOpensHttp() throws Exception {
        try (var f = new Fixture("{}", 500, "application/json")) {
            for (String missing : new String[]{null, "", "   ", "${OPENAI_API_KEY}"}) {
                var res = f.apiKeyModel(missing).chat(List.of(UserMessage.from("ping")));
                assertNotNull(res.aiMessage().text());
            }
            assertEquals(0, f.calls.get(), "no-credential request opened an HTTP call");
        }
    }

    /** The API-key path keeps its negotiated options and never gains OAuth-only flags. */
    @Test void apiKeyPayloadKeepsOptionsAndNoOauthFlags() throws Exception {
        try (var f = new Fixture("{\"status\":\"completed\",\"output_text\":\"ok\"}",
                200, "application/json")) {
            var model = f.apiKeyModel("fixture-key");
            model.doChat(ChatRequest.builder()
                    .messages(UserMessage.from("hi")).maxOutputTokens(64).build());
            JsonNode b = f.body.get();
            assertEquals(64, b.path("max_output_tokens").asInt(),
                    "Codex must not strip the API-key path's negotiated output cap");
            assertFalse(b.has("store") || b.has("stream"),
                    "store/stream are OAuth-transport flags, not API-key payload keys");
            assertEquals("Bearer fixture-key", f.auth.get());
            assertTrue(f.accept.get().contains("application/json"));
            assertFalse(f.accept.get().contains("text/event-stream"));
        }
    }

    /** Terminal Responses failures are already non-replayable, even wrapped. */
    @Test void terminalExceptionIsAlreadyNonReplayable() {
        var terminal = new LlmResponseTerminalException("responses_failed",
                LlmFailureClass.PROVIDER_ERROR, null, null, "failed", null,
                "subscription_sharing_usage_limit_exceeded");
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(terminal));
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(
                new RuntimeException("outer", terminal)));
    }

    /** Trace surfaces must never retain the credential after an HTTP failure. */
    @Test void failedCallLeavesNoCredentialInTrace() {
        String key = "sk-fixture-" + "k".repeat(24);
        try (var f = new Fixture("{\"error\":{\"code\":\"server_error\"}}",
                500, "application/json")) {
            f.apiKeyModel(key).chat(List.of(UserMessage.from("hi")));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(key), "api key reached TraceStore");
            assertFalse(trace.contains("Bearer " + key));
        } catch (Exception e) {
            fail("fixture failure: " + e);
        }
    }

    /** Boundary redaction masks Bearer headers and key=value token forms. */
    @Test void redactionMasksBearerAndKeyValueSecrets() {
        String bearer = "Bearer " + OAUTH_TOKEN;
        assertFalse(PromptMasker.mask("Authorization: " + bearer).contains("synthetic"));
        assertFalse(SafeRedactor.redact("h " + bearer).contains("synthetic"));
        String rt = "rt_" + "r".repeat(24);
        assertFalse(PromptMasker.mask("refresh_token=" + rt).contains(rt));
        assertFalse(PromptMasker.mask("access_token: " + OAUTH_TOKEN).contains(OAUTH_TOKEN));
    }

    // ---------- GATE (activate when the Codex OAuth transport seam exists) ----------

    /** OAuth wire body never carries spec-forbidden params, even when the caller sets them. */
    @Test void forbiddenParamsRejected() throws Exception {
        assumeOauthSeam();
        try (var f = new Fixture(SSE_COMPLETED, 200, "text/event-stream")) {
            f.oauthModel(() -> OAUTH_TOKEN).doChat(ChatRequest.builder()
                    .messages(List.of(SystemMessage.from("s"), UserMessage.from("u"),
                            AiMessage.from("a")))
                    .maxOutputTokens(9).temperature(0.9).topP(0.3).build());
            JsonNode b = f.body.get();
            String raw = b.toString();
            for (String k : FORBIDDEN_WIRE_KEYS) {
                assertFalse(b.has(k), "forbidden OAuth param sent: " + k);
                assertFalse(raw.contains("\"" + k + "\""), "forbidden key leaked as text: " + k);
            }
            assertTrue(b.has("model") && b.has("input"));
        }
    }

    /** store=false + stream=true + SSE Accept are forced on every OAuth dispatch. */
    @Test void storeFalseStreamTrueEnforced() throws Exception {
        assumeOauthSeam();
        try (var f = new Fixture(SSE_COMPLETED, 200, "text/event-stream")) {
            var model = f.oauthModel(() -> OAUTH_TOKEN);
            for (int i = 0; i < 2; i++) {
                model.chat(List.of(UserMessage.from("q" + i)));
                JsonNode b = f.body.get();
                assertTrue(b.has("store") && !b.get("store").asBoolean(), "store must be pinned false");
                assertTrue(b.has("stream") && b.get("stream").asBoolean(), "stream must be pinned true");
                assertEquals("text/event-stream", f.accept.get());
                assertEquals("Bearer " + OAUTH_TOKEN, f.auth.get());
            }
            assertEquals(2, f.calls.get());
        }
    }

    /**
     * Billing-leak guard: an OAuth terminal failure must classify non-replayable
     * so the ChatWorkflow fail-soft (ChatWorkflow.java:7067-7076) propagates it
     * instead of re-issuing on a paid API-key route. Zero paid-endpoint calls.
     */
    @Test void billingLeakZero() throws Exception {
        assumeOauthSeam();
        try (var oauth = new Fixture(
                     "{\"error\":{\"code\":\"subscription_sharing_user_not_eligible\"}}",
                     403, "application/json");
             var paid = new Fixture("{\"status\":\"completed\",\"output_text\":\"paid\"}",
                     200, "application/json")) {
            var model = oauth.oauthModel(() -> OAUTH_TOKEN);
            var err = assertThrows(LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("q"))));
            // Mirror of the ChatWorkflow decision rule: non-replayable -> propagate.
            if (!LlmGatewayFailureClassifier.hasNonReplayableReason(err)) {
                paid.apiKeyModel("sk-fallback-" + "f".repeat(20))
                        .chat(List.of(UserMessage.from("q")));
            }
            assertEquals(1, oauth.calls.get(), "OAuth request must not be retried");
            assertEquals(0, paid.calls.get(),
                    "billing leak: OAuth failure silently fell back to a paid API key");
            assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(err),
                    "subscription_sharing_* must be non-replayable");
        }
    }

    /**
     * modelsKeyParsedVerbatim: the OAuth /v1/models shape returns models[] (not the
     * API-key data[]). Gate scans product source for a ChatGPT OAuth catalog seam;
     * once present, that seam must read the "models" key — a data[]-only parser
     * silently drops the whole OAuth catalog.
     */
    @Test void modelsKeyParsedVerbatim() throws Exception {
        Path mainJava = Path.of("main", "java");
        assumeTrue(Files.isDirectory(mainJava), "project root expected as test cwd");
        List<Path> seams = new ArrayList<>();
        try (var walk = Files.walk(mainJava)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (!p.toString().endsWith(".java")) continue;
                String s = Files.readString(p, StandardCharsets.UTF_8);
                boolean oauth = s.contains("ChatGpt") || s.contains("chatgpt")
                        || s.contains("ext_agent_host_id");
                boolean catalog = s.contains("/models") || s.contains("models-file")
                        || s.contains("modelsFile") || s.contains("sync-models")
                        || s.contains("models.json") || s.contains("oauthModels");
                if (oauth && catalog) {
                    seams.add(p);
                }
            }
        }
        assumeTrue(!seams.isEmpty(),
                "GATE-LOCKED: unlocks when Codex WP1 adds the OAuth model catalog seam");
        for (Path p : seams) {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            assertTrue(s.contains("\"models\""),
                    p + " touches ChatGPT OAuth models but never reads the models[] key");
        }
    }

    /** OAuth bearer material must not survive into exception text or trace state. */
    @Test void tokenRedactedInExceptions() throws Exception {
        assumeOauthSeam();
        try (var f = new Fixture(
                "{\"error\":{\"code\":\"subscription_sharing_user_not_eligible\"}}",
                403, "application/json")) {
            var model = f.oauthModel(() -> OAUTH_TOKEN);
            var err = assertThrows(LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("q"))));
            assertFalse(err.toString().contains(OAUTH_TOKEN));
            assertFalse(String.valueOf(err.getMessage()).contains(OAUTH_TOKEN));
            assertFalse(String.valueOf(TraceStore.getAll()).contains(OAUTH_TOKEN));
            assertFalse(model.toString().contains(OAUTH_TOKEN));
        }
    }

    // ---------- Bare-token redaction contracts ----------

    /** Bare JWTs must be masked even without a Bearer label. */
    @Test
    void bareJwtNeverSurvivesRedaction() {
        String jwt = "eyJ" + "h".repeat(20) + "." + "p".repeat(20) + "." + "s".repeat(16);
        assertFalse(PromptMasker.mask("err " + jwt).contains(jwt),
                "bare JWT in an error body reaches logs unmasked");
        assertFalse(SafeRedactor.safeMessage("err " + jwt, 400).contains(jwt));
    }

    /** Bare refresh tokens must be masked even without a credential field label. */
    @Test
    void bareRefreshTokenNeverSurvivesRedaction() {
        String rt = "rt_" + "r".repeat(28);
        assertFalse(PromptMasker.mask("leaked " + rt).contains(rt),
                "bare rt_ refresh token reaches logs unmasked");
        assertFalse(SafeRedactor.safeMessage("leaked " + rt, 400).contains(rt));
    }
    @Test void ordinaryShortRtWordsAndDottedTextRemainUnchanged() {
        String jwtHead = "eyJ" + "h".repeat(20);
        for (String ordinary : List.of("keep rt_ and rt_status in this sentence", "alpha.beta.gamma", "v1.2.3",
                jwtHead + "." + "p".repeat(20), "rt_" + "r".repeat(15))) {
            assertEquals(ordinary, PromptMasker.mask(ordinary), "ordinary text must survive the narrower token patterns");
        }
    }
    @Test void bareTokenMaskPreservesLengthPunctuationAndIdempotence() {
        String jwt = "eyJ" + "h".repeat(20) + "." + "p_-".repeat(8) + "." + "s".repeat(16);
        String rt = "rt_" + "r_-".repeat(10);
        String input = "앞(" + jwt + "), " + rt + ". 뒤";
        String expected = "앞(" + "*".repeat(jwt.length()) + "), " + "*".repeat(rt.length()) + ". 뒤";
        String masked = PromptMasker.mask(input);
        assertEquals(expected, masked);
        assertEquals(input.length(), masked.length());
        assertEquals(masked, PromptMasker.mask(masked));
        String longJwt = "eyJ" + "h".repeat(20_000) + "." + "p".repeat(20_000) + "." + "s".repeat(20_000);
        assertEquals("*".repeat(longJwt.length()), PromptMasker.mask(longJwt));
    }
}
