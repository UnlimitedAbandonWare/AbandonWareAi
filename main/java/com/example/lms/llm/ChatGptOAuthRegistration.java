package com.example.lms.llm;

import ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmResponseTerminalException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Read-only consumer of the Devin harness contract. OAuth never enters the API-key route map. */
@Component
public class ChatGptOAuthRegistration {
    public static final String PREFIX = "chatgpt-oauth:";
    public static final String PROVIDER = "chatgpt_oauth";
    public static final String BILLING_SOURCE = "CHATGPT_OAUTH_PLAN";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final boolean enabled;
    private final Path credentials, catalog;
    private final long refreshBufferSeconds;
    private final Clock clock;
    private final TokenRefresh refresh;
    @Autowired(required = false)
    private com.example.lms.repository.ConfigurationSettingRepository benefitSettings;
    @Value("${chatgpt.oauth.account-ref:}")
    private String accountRef = "";
    @Value("${chatgpt.oauth.benefit.reported-grant-credits:#{null}}")
    private Long reportedGrantCredits;
    @Value("${chatgpt.oauth.benefit.reported-expires-on:}")
    private String reportedExpiresOn = "";
    @Value("${chatgpt.oauth.benefit.operational-cutoff:}")
    private String operationalCutoff = "";
    private final java.util.Set<String> usageBlockedAccounts = new java.util.HashSet<>();
    private boolean warnedUnboundBenefit;
    @Value("${chatgpt.oauth.main-model:}")
    private String mainModel = "";
    @Value("${chatgpt.oauth.main-routing-enabled:true}")
    private boolean mainRoutingEnabled = true;
    @Value("${chatgpt.oauth.own-account-only:false}")
    private boolean ownAccountOnly;
    @Value("${chatgpt.oauth.owner-hash:}")
    private String registeredOwnerHash = "";

    /** Explicit failover ownership; independent of the general catalogue visibility policy. */
    public boolean isRegisteredOwner(String ownerHash) {
        return ownerHash != null && ownerHash.matches("[0-9a-f]{64}") && ownerHash.equals(registeredOwnerHash);
    }

    private boolean ownerAllowed(String ownerHash) {
        return !ownAccountOnly || ownerHash != null && ownerHash.matches("[0-9a-f]{64}")
                && ownerHash.equals(registeredOwnerHash) && accountRef != null && !accountRef.isBlank();
    }

    /** Harness-compatible local attribution only; decoding a subject does not verify OAuth identity. */
    private static String accountFingerprint(JsonNode credentials) {
        String source = "";
        try {
            String[] parts = credentials.path("id_token").asText("").split("[.]");
            if (parts.length >= 2) {
                JsonNode subject = JSON.readTree(java.util.Base64.getUrlDecoder().decode(parts[1])).path("sub");
                if (subject.isTextual()) source = subject.textValue();
            }
        } catch (Exception invalidPayload) { /* Use the harness's safe fallback precedence. */ }
        for (String field : List.of("refresh_token", "access_token", "client_id")) {
            if (!source.isEmpty()) break;
            JsonNode value = credentials.path(field);
            if (value.isTextual()) source = value.textValue();
        }
        if (source.isEmpty()) return null;
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException unavailable) { return null; }
    }

    private boolean accountMatches(JsonNode credentials, JsonNode catalogue) {
        if (!ownAccountOnly) return true;
        String fingerprint = accountFingerprint(credentials);
        return fingerprint != null && fingerprint.equals(accountRef)
                && fingerprint.equals(catalogue.path("catalog_account_fingerprint").asText(""));
    }

    public synchronized String automaticMainRoute() {
        if (!mainRoutingEnabled) return null;
        var models = models();
        if (models.isEmpty()) return null;
        // Missing benefit observations never disable a normally configured subscription route.
        if (benefitKey() != null && !benefitSnapshot().active()) return null;
        String slug = mainModel == null || mainModel.isBlank() ? models.get(0) : mainModel;
        return models.contains(slug) ? route(slug) : null;
    }

    /** User report, observations and estimates have distinct units; unknown values stay null. */
    public record BenefitSnapshot(Long reportedGrantCredits, String unit, String reportedExpiresOn,
            java.time.Instant expiresAt, java.time.Instant operationalCutoff, Long observedRemaining,
            Long observedTokens, Long estimatedCredits, Long actualDebitedCredits,
            boolean exhausted, boolean storageObserved, boolean active, String bindingState,
            boolean usageBlocked, String blockedReason) {}

    private String benefitKey() {
        String account = accountRef;
        if (account == null || account.isBlank()) {
            JsonNode catalogue = read(catalog);
            JsonNode fingerprint = catalogue.path("catalog_account_fingerprint");
            account = "awx.chatgpt-oauth-models.v1".equals(catalogue.path("schemaVersion").asText())
                    && "synced".equals(catalogue.path("status").asText()) && fingerprint.isTextual()
                    && fingerprint.textValue().matches("[0-9a-f]{12}") ? fingerprint.textValue() : null;
        }
        if (account == null) {
            if (!warnedUnboundBenefit) {
                warnedUnboundBenefit = true;
                org.slf4j.LoggerFactory.getLogger(ChatGptOAuthRegistration.class)
                        .warn("ChatGPT OAuth benefit unbound: account key unavailable");
            }
            return null;
        }
        return "OAUTH_BENEFIT_" + com.example.lms.service.AttachmentOwnerIdentity
                .forAnonymous("chatgpt-reported-grant:" + account).hash();
    }

    public synchronized BenefitSnapshot benefitSnapshot() {
        String key = benefitKey();
        boolean exhausted = false; // Compatibility with a legacy stored label, never an inferred balance.
        boolean usageBlocked = key != null && usageBlockedAccounts.contains(key);
        boolean observed = key != null && benefitSettings != null;
        if (observed) {
            try {
                String state = benefitSettings.findById(key)
                        .map(row -> row.getSettingValue()).orElse(null);
                exhausted = "exhausted".equals(state);
                usageBlocked |= exhausted || "usage_limit_recheck_required".equals(state);
            } catch (RuntimeException unavailable) { observed = false; }
        }
        java.time.Instant cutoff = null;
        boolean validCutoff = true;
        if (operationalCutoff != null && !operationalCutoff.isBlank()) {
            try { cutoff = java.time.Instant.parse(operationalCutoff); }
            catch (RuntimeException invalid) { validCutoff = false; }
        }
        return new BenefitSnapshot(reportedGrantCredits, "credits",
                reportedExpiresOn == null || reportedExpiresOn.isBlank() ? null : reportedExpiresOn,
                null, cutoff, null, null, null, null, exhausted, observed,
                enabled && observed && validCutoff && !usageBlocked && (cutoff == null || clock.instant().isBefore(cutoff)),
                key == null ? "unbound" : "bound", usageBlocked,
                usageBlocked ? "usage_limit_recheck_required" : null);
    }

    /** Persist only a provider-confirmed usage limit; never decrement a reported grant by tokens. */
    public synchronized void observeTerminal(LlmResponseTerminalException failure) {
        observeTerminalFor(benefitKey(), failure);
    }
    private synchronized void observeTerminalFor(String key, LlmResponseTerminalException failure) {
        if (failure == null || !"subscription_sharing_usage_limit_exceeded".equals(failure.providerCode())) return;
        if (key == null) return;
        usageBlockedAccounts.add(key);
        try {
            if (benefitSettings == null) throw new IllegalStateException("benefit_store_unavailable");
            benefitSettings.save(new com.example.lms.domain.ConfigurationSetting(key, "usage_limit_recheck_required"));
        } catch (RuntimeException unavailable) {
            com.example.lms.search.TraceStore.put("chatgpt.oauth.benefit.persist", "evidence_needed");
        }
    }

    @FunctionalInterface
    public interface TokenRefresh { boolean run(long timeoutMs) throws Exception; }

    @Autowired
    public ChatGptOAuthRegistration(
            @Value("${chatgpt.oauth.enabled:false}") boolean enabled,
            @Value("${chatgpt.oauth.credentials-file:.secrets/chatgpt_oauth_credentials.json}") String credentials,
            @Value("${chatgpt.oauth.models-file:data/agent-handoff/chatgpt-oauth/models.json}") String catalog,
            @Value("${chatgpt.oauth.refresh-buffer-seconds:300}") long refreshBufferSeconds) {
        this.enabled = enabled;
        this.credentials = Path.of(credentials).toAbsolutePath().normalize();
        this.catalog = Path.of(catalog).toAbsolutePath().normalize();
        this.refreshBufferSeconds = Math.max(0, refreshBufferSeconds);
        this.clock = Clock.systemUTC();
        this.refresh = this::refreshWithHarness;
    }

    /** Isolated fixture seam; never changes process-wide configuration or a real credential store. */
    public ChatGptOAuthRegistration(boolean enabled, Path credentials, Path catalog, long buffer,
            Clock clock, TokenRefresh refresh) {
        this.enabled = enabled; this.credentials = credentials; this.catalog = catalog;
        this.refreshBufferSeconds = Math.max(0, buffer); this.clock = clock; this.refresh = refresh;
    }

    public static boolean isRoute(String id) { return id != null && id.startsWith(PREFIX); }
    public static String route(String slug) { return PREFIX + slug; }
    public static String model(String route) { return isRoute(route) ? route.substring(PREFIX.length()) : ""; }
    private static boolean validSlug(String slug) {
        return slug != null && slug.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}");
    }

    /** Metadata only: no refresh, discovery, browser, or generation from a picker/admission check. */
    public List<String> models() {
        return models(RequestedModelSelection.ownerHash());
    }
    public List<String> models(String ownerHash) {
        if (!ownerAllowed(ownerHash)) return List.of();
        JsonNode credentialDoc = read(credentials);
        if (!enabled || !usableSession(credentialDoc)) return List.of();
        JsonNode doc = read(catalog);
        if (!accountMatches(credentialDoc, doc)) return List.of();
        if (!"awx.chatgpt-oauth-models.v1".equals(doc.path("schemaVersion").asText())
                || !"synced".equals(doc.path("status").asText()) || !doc.path("models").isArray())
            return List.of();
        var result = new ArrayList<String>();
        for (JsonNode row : doc.path("models")) {
            if (!row.isTextual() || !validSlug(row.textValue())) continue;
            if (!result.contains(row.textValue())) result.add(row.textValue());
        }
        return List.copyOf(result);
    }

    public boolean available(String route) {
        return isRoute(route) && models().contains(model(route));
    }

    public synchronized ChatModel modelFor(String route, long timeoutMs) {
        String ownerHash=RequestedModelSelection.ownerHash();
        if (!ownerAllowed(ownerHash)) throw unavailable("chatgpt_oauth_owner_mismatch");
        checkDispatchBudget(timeoutMs);
        if (enabled && !directScopeGranted(read(credentials))) throw unavailable("chatgpt_oauth_scope_missing");
        if (!available(route)) throw unavailable("chatgpt_oauth_not_configured");
        checkUsageBlock();
        String slug = model(route);
        String admittedAccount=benefitKey();
        return new OpenAiResponsesChatModel("https://api.openai.com/v1", slug, timeoutMs,
                () -> accessTokenFor(route, timeoutMs, ownerHash, admittedAccount),
                failure -> observeTerminalFor(admittedAccount, failure));
    }

    private synchronized String accessTokenFor(String route, long timeoutMs, String ownerHash, String admittedAccount) {
        if (!ownerAllowed(ownerHash) || !java.util.Objects.equals(admittedAccount, benefitKey()))
            throw unavailable("chatgpt_oauth_owner_mismatch");
        return accessTokenForOwner(route, timeoutMs, ownerHash);
    }

    /** Re-read on every dispatch; the harness remains the only credential writer. */
    public synchronized String accessToken(String route, long timeoutMs) {
        return accessTokenForOwner(route, timeoutMs, RequestedModelSelection.ownerHash());
    }
    private String accessTokenForOwner(String route, long timeoutMs, String ownerHash) {
        if (!ownerAllowed(ownerHash)) throw unavailable("chatgpt_oauth_owner_mismatch");
        checkDispatchBudget(timeoutMs);
        if (enabled && !directScopeGranted(read(credentials))) throw unavailable("chatgpt_oauth_scope_missing");
        if (!isRoute(route) || !models(ownerHash).contains(model(route))) throw unavailable("chatgpt_oauth_not_configured");
        checkUsageBlock();
        JsonNode doc = read(credentials);
        if (!fresh(doc)) {
            if (!usableSecret(doc, "refresh_token"))
                throw unavailable("chatgpt_oauth_refresh_required");
            try {
                boolean refreshed = refresh.run(timeoutMs);
                checkDispatchBudget(timeoutMs);
                if (!refreshed) throw unavailable("chatgpt_oauth_refresh_failed");
            } catch (LlmResponseTerminalException terminal) { throw terminal; }
            catch (Exception failed) {
                if (failed instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw new LlmResponseTerminalException("chatgpt_oauth_cancelled", LlmFailureClass.CANCELLED_NEUTRAL,
                            null, null, "cancelled", null, null);
                }
                if (failed instanceof java.util.concurrent.TimeoutException)
                    throw new LlmResponseTerminalException("chatgpt_oauth_deadline_exhausted", LlmFailureClass.TIMEOUT_SOFT,
                            null, null, "failed", null, null);
                throw unavailable("chatgpt_oauth_refresh_failed");
            }
            doc = read(credentials);
        }
        if (!accountMatches(doc, read(catalog))) throw unavailable("chatgpt_oauth_owner_mismatch");
        if (!enabled || !usableSession(doc) || !fresh(doc) || !models(ownerHash).contains(model(route)))
            throw unavailable("chatgpt_oauth_credential_unavailable");
        return doc.path("access_token").textValue();
    }

    private boolean usableSession(JsonNode doc) {
        return "awx.chatgpt-oauth.v1".equals(doc.path("schemaVersion").asText())
                && directScopeGranted(doc)
                && "Bearer".equalsIgnoreCase(doc.path("token_type").asText("Bearer"))
                && usableSecret(doc, "access_token")
                && doc.path("expires_at").isNumber()
                && (fresh(doc) || usableSecret(doc, "refresh_token"));
    }

    private static boolean directScopeGranted(JsonNode doc) {
        JsonNode scope = doc.path("scope");
        return scope.isTextual() && scope.textValue().length() <= 4096
                && java.util.Arrays.stream(scope.textValue().split("\\s+"))
                        .anyMatch("chatgpt.tokens.use.direct"::equals);
    }

    private static void checkDispatchBudget(long timeoutMs) {
        if (Thread.currentThread().isInterrupted())
            throw new LlmResponseTerminalException("chatgpt_oauth_cancelled", LlmFailureClass.CANCELLED_NEUTRAL,
                    null, null, "cancelled", null, null);
        if (timeoutMs <= 0)
            throw new LlmResponseTerminalException("chatgpt_oauth_deadline_exhausted", LlmFailureClass.TIMEOUT_SOFT,
                    null, null, "failed", null, null);
    }

    private void checkUsageBlock() {
        if (benefitSnapshot().usageBlocked())
            throw new LlmResponseTerminalException("chatgpt_oauth_usage_recheck_required", LlmFailureClass.RATE_LIMIT_COOLDOWN,
                    null, null, "failed", null, null);
    }

    private boolean fresh(JsonNode doc) {
        return doc.path("expires_at").asDouble(0) - clock.instant().getEpochSecond() > refreshBufferSeconds;
    }

    private static boolean usableSecret(JsonNode doc, String field) {
        JsonNode value = doc.path(field);
        return value.isTextual() && !ConfigValueGuards.isMissing(value.textValue())
                && value.textValue().length() <= 32768
                && value.textValue().chars().noneMatch(Character::isWhitespace);
    }

    private static JsonNode read(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 1_048_576)
                return JSON.createObjectNode();
            JsonNode value = JSON.readTree(Files.readAllBytes(file));
            return value != null && value.isObject() ? value : JSON.createObjectNode();
        } catch (Exception unavailable) {
            // No path, parser diagnostic, token, or provider body is retained.
            return JSON.createObjectNode();
        }
    }

    private boolean refreshWithHarness(long timeoutMs) throws Exception {
        // A custom credential path must never accidentally refresh a different real account.
        Path canonical = Path.of(".secrets/chatgpt_oauth_credentials.json").toAbsolutePath().normalize();
        Path harness = Path.of("scripts/chatgpt_oauth_flow.py").toAbsolutePath().normalize();
        if (!canonical.equals(credentials) || !Files.isRegularFile(harness)) return false;
        Process process = new ProcessBuilder("python", "-B", harness.toString(),
                "--root", Path.of(".").toAbsolutePath().normalize().toString(),
                "--buffer", Long.toString(refreshBufferSeconds), "get-token")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS))
                throw new java.util.concurrent.TimeoutException("chatgpt_oauth_deadline_exhausted");
            return process.exitValue() == 0;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static LlmResponseTerminalException unavailable(String reason) {
        return new LlmResponseTerminalException(reason, LlmFailureClass.AUTH_MISSING,
                null, null, "failed", null, null);
    }
}
