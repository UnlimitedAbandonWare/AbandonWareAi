package ai.abandonware.nova.boot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Property alias "glue" to reduce silent misconfiguration across modules.
 *
 * <p>
 * Why:
 * - Operators may set only env vars (OPENAI_API_KEY / OPENAI_BASE_URL)
 * - Some configs use flat keys (llm.api-key-openai / llm.base-url-openai)
 * - Some code paths use nested keys (llm.openai.api-key / llm.openai.base-url)
 * - Some libraries or merged patches use other spellings (openai.api.key,
 * spring.ai.openai.api-key, ...)
 *
 * <p>
 * This post-processor primarily provides safe aliases when the target key is
 * blank/missing.
 * Additionally, it can reconcile a small set of known-bad conflicts (e.g.,
 * duplicate OpenAI API key keys)
 * to keep the server responsive. It never logs secret values.
 */
public class NovaPropertyAliasEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(NovaPropertyAliasEnvironmentPostProcessor.class);

    private static final String ALIAS_SOURCE_NAME = "novaPropertyAliases";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (env == null) {
            return;
        }

        Map<String, Object> alias = new LinkedHashMap<>();

        // ---- OpenAI API Key aliases ----
        // IMPORTANT:
        // - com.example.lms.guard.KeyResolver enforces a strict rule:
        // set only ONE of { llm.api-key-openai, llm.openai.api-key, OPENAI_API_KEY }
        // - Earlier patches populated multiple keys for convenience/compatibility,
        // which
        // unintentionally triggers KeyResolver's fail-fast path.
        //
        // Fix strategy (fail-soft, secrets redacted):
        // 1) Pick a single "winner" key (prefer OPENAI_API_KEY when present)
        // 2) Normalize the chosen secret (trim + strip surrounding quotes)
        // 3) Blank out the other strict keys via a high-precedence property source
        //
        // This keeps the server responsive while still nudging operators to configure
        // only one key in the long run.

        String rawEnvKey = rawTrimToNull(env.getProperty("OPENAI_API_KEY"));
        String rawFlatKey = rawTrimToNull(env.getProperty("llm.api-key-openai"));
        String rawNestedKey = rawTrimToNull(env.getProperty("llm.openai.api-key"));

        String envKey = normalizeNonPlaceholder(rawEnvKey);
        String flatKey = normalizeNonPlaceholder(rawFlatKey);
        String nestedKey = normalizeNonPlaceholder(rawNestedKey);

        // Fallback: accept common compatibility spellings as inputs.
        String compatKey = normalizeNonPlaceholder(firstNonBlankFromEnv(env,
                "openai.api.key",
                "openai.api-key",
                "spring.ai.openai.api-key"));

        // Choose a single winner key name.
        String winnerKeyName;
        if (envKey != null) {
            winnerKeyName = "OPENAI_API_KEY";
        } else if (flatKey != null) {
            winnerKeyName = "llm.api-key-openai";
        } else if (nestedKey != null) {
            winnerKeyName = "llm.openai.api-key";
        } else {
            // No strict key is set; if we have a compat key, bind it to our documented flat
            // key.
            winnerKeyName = "llm.api-key-openai";
        }

        String winnerValue = switch (winnerKeyName) {
            case "OPENAI_API_KEY" -> envKey;
            case "llm.api-key-openai" -> (flatKey != null ? flatKey : compatKey);
            case "llm.openai.api-key" -> nestedKey;
            default -> null;
        };

        // Diagnostics (never log secret values).
        int present = 0;
        if (envKey != null)
            present++;
        if (flatKey != null)
            present++;
        if (nestedKey != null)
            present++;

        if (present > 1) {
            boolean different = false;
            String first = (envKey != null) ? envKey : (flatKey != null ? flatKey : nestedKey);
            if (envKey != null && !envKey.equals(first))
                different = true;
            if (flatKey != null && !flatKey.equals(first))
                different = true;
            if (nestedKey != null && !nestedKey.equals(first))
                different = true;

            if (different) {
                log.error(
                        "[NovaAlias] Conflicting OpenAI API keys detected across strict keys. " +
                                "To satisfy KeyResolver, keeping only '{}' and blanking the others (values are redacted). "
                                +
                                "Please set only ONE of: llm.api-key-openai / llm.openai.api-key / OPENAI_API_KEY.",
                        winnerKeyName);
            } else {
                log.warn(
                        "[NovaAlias] Duplicate OpenAI API key configured in multiple strict keys. " +
                                "To satisfy KeyResolver, keeping only '{}' and blanking the others (values are redacted).",
                        winnerKeyName);
            }
        }

        if (winnerValue != null) {
            // Enforce "only one" visibility for strict KeyResolver.
            forceBlankIfPresent(env, alias, "llm.api-key-openai", winnerKeyName);
            forceBlankIfPresent(env, alias, "llm.openai.api-key", winnerKeyName);
            forceBlankIfPresent(env, alias, "OPENAI_API_KEY", winnerKeyName);

            // Force winner to a normalized value (helps when env/config has quotes).
            alias.put(winnerKeyName, winnerValue);

            // Populate non-strict compatibility keys (safe, KeyResolver does not check
            // these).
            putIfBlank(env, alias, "openai.api.key", winnerValue);
            putIfBlank(env, alias, "openai.api-key", winnerValue);
            putIfBlank(env, alias, "spring.ai.openai.api-key", winnerValue);
        } else {
            // No key found at all; still warn if two llm.* keys conflict (redacted).
            warnIfConflicting(env, "llm.api-key-openai", "llm.openai.api-key");
        }

        // ---- OpenAI base URL aliases ----
        warnIfConflicting(env, "llm.base-url-openai", "llm.openai.base-url");

        String openAiBaseUrl = firstNonBlankFromEnv(env,
                "llm.base-url-openai",
                "llm.openai.base-url",
                "openai.api.url",
                "openai.base-url",
                "spring.ai.openai.base-url",
                "OPENAI_BASE_URL");

        putIfBlank(env, alias, "llm.base-url-openai", openAiBaseUrl);
        putIfBlank(env, alias, "llm.openai.base-url", openAiBaseUrl);
        putIfBlank(env, alias, "openai.api.url", openAiBaseUrl);
        putIfBlank(env, alias, "openai.base-url", openAiBaseUrl);
        putIfBlank(env, alias, "spring.ai.openai.base-url", openAiBaseUrl);
        putIfBlank(env, alias, "OPENAI_BASE_URL", openAiBaseUrl);

        // ---- Local(OpenAI-compatible) base URL aliases (Ollama/vLLM) ----
        warnIfConflicting(env, "llm.base-url", "llm.ollama.base-url");

        String localBaseUrl = firstNonBlankFromEnv(env,
                "llm.base-url",
                "llm.ollama.base-url",
                "OLLAMA_BASE_URL");
        putIfBlank(env, alias, "llm.base-url", localBaseUrl);
        putIfBlank(env, alias, "llm.ollama.base-url", localBaseUrl);
        putIfBlank(env, alias, "OLLAMA_BASE_URL", localBaseUrl);

        // ---- LLM model id aliases (prevents empty env override -> downstream "model
        // is required") ----
        // NOTE: Spring placeholder defaults (${ENV:${fallback}}) do NOT apply when ENV
        // is present-but-empty.
        // That pattern frequently results in resolved "" (blank) model ids.
        // OpenAI-compatible servers then respond
        // with: {"error":{"message":"model is required"...}}.
        String chatModel = rawTrimToNull(env.getProperty("llm.chat-model"));
        if (chatModel != null && chatModel.contains("${") && chatModel.contains("}")) {
            chatModel = null; // leaked placeholder string
        }
        if (isBlank(chatModel)) {
            // Common env fallbacks when operators don't want to edit YAML
            chatModel = rawTrimToNull(firstNonBlankFromEnv(env,
                    "LLM_CHAT_MODEL",
                    "LLM_MODEL",
                    "OLLAMA_MODEL",
                    "MODEL"));
        }

        String fastModel = rawTrimToNull(env.getProperty("llm.fast.model"));
        if (fastModel != null && fastModel.contains("${") && fastModel.contains("}")) {
            fastModel = null;
        }
        if (isBlank(fastModel)) {
            fastModel = rawTrimToNull(firstNonBlankFromEnv(env, "LLM_FAST_MODEL"));
        }
        if (isBlank(fastModel)) {
            // If fast model is blank, fall back to the primary chat model.
            fastModel = chatModel;
        }

        // Apply only when the target key is currently blank
        putIfBlank(env, alias, "llm.chat-model", chatModel);
        putIfBlank(env, alias, "llm.fast.model", fastModel);

        // ---- LLM fast base-url fallback (same blank-ENV issue as model id) ----
        String baseUrl = rawTrimToNull(env.getProperty("llm.base-url"));
        String fastBaseUrl = rawTrimToNull(env.getProperty("llm.fast.base-url"));
        if (fastBaseUrl != null && fastBaseUrl.contains("${") && fastBaseUrl.contains("}")) {
            fastBaseUrl = null;
        }
        if (isBlank(fastBaseUrl) && !isBlank(baseUrl)) {
            putIfBlank(env, alias, "llm.fast.base-url", baseUrl);
        }

        // ---- Hybrid websearch min-live-budget alias (stuff6)
        // Allow legacy/short key: gpt-search.hybrid.min-live-budget-ms
        String minLiveBudgetMs = rawTrimToNull(env.getProperty("gpt-search.hybrid.await.min-live-budget-ms"));
        if (isBlank(minLiveBudgetMs))
            minLiveBudgetMs = rawTrimToNull(env.getProperty("gpt-search.hybrid.min-live-budget-ms"));
        if (isBlank(minLiveBudgetMs))
            minLiveBudgetMs = rawTrimToNull(env.getProperty("nova.orch.web.failsoft.min-live-budget-ms"));
        putIfBlank(env, alias, "gpt-search.hybrid.await.min-live-budget-ms", minLiveBudgetMs);

        // ---------------------------------------------------------------------
        // Brave API key/subscription-token resolver (multi-token, fail-soft):
        // - Prevent blank property masking ENV (Spring placeholder default won't apply to empty string)
        // - Avoid false multi-source detections caused by Spring relaxed binding
        // - Allow BOTH BRAVE_API_KEY and BRAVE_SUBSCRIPTION_TOKEN to be present without disabling
        // - Pick a deterministic winner by priority and backfill canonical property keys
        //
        // Brave Search API uses "X-Subscription-Token" header, so we support both:
        // - ...api-key (legacy)
        // - ...subscription-token (preferred)
        // ---------------------------------------------------------------------
        String rawBraveApiPrimary = rawTrimToNull(getNonEnvProperty(env, "gpt-search.brave.api-key"));
        String rawBraveApiLegacy = rawTrimToNull(getNonEnvProperty(env, "search.brave.api-key"));
        String rawBraveSubPrimary = rawTrimToNull(getNonEnvProperty(env, "gpt-search.brave.subscription-token"));
        String rawBraveSubLegacy = rawTrimToNull(getNonEnvProperty(env, "search.brave.subscription-token"));

        String rawBraveEnvApiA = rawTrimToNull(System.getenv("GPT_SEARCH_BRAVE_API_KEY"));
        String rawBraveEnvApiB = rawTrimToNull(System.getenv("BRAVE_API_KEY"));
        String rawBraveEnvSubC = rawTrimToNull(System.getenv("GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN"));
        String rawBraveEnvSubD = rawTrimToNull(System.getenv("BRAVE_SUBSCRIPTION_TOKEN"));

        String braveApiPrimary = normalizeNonPlaceholder(rawBraveApiPrimary);
        String braveApiLegacy = normalizeNonPlaceholder(rawBraveApiLegacy);
        String braveSubPrimary = normalizeNonPlaceholder(rawBraveSubPrimary);
        String braveSubLegacy = normalizeNonPlaceholder(rawBraveSubLegacy);

        String braveEnvApiA = normalizeNonPlaceholder(rawBraveEnvApiA);
        String braveEnvApiB = normalizeNonPlaceholder(rawBraveEnvApiB);
        String braveEnvSubC = normalizeNonPlaceholder(rawBraveEnvSubC);
        String braveEnvSubD = normalizeNonPlaceholder(rawBraveEnvSubD);

        java.util.LinkedHashMap<String, String> braveApiSources = new java.util.LinkedHashMap<>();
        if (!isBlank(braveApiPrimary))
            braveApiSources.put("gpt-search.brave.api-key", braveApiPrimary);
        if (!isBlank(braveApiLegacy))
            braveApiSources.put("search.brave.api-key", braveApiLegacy);
        if (!isBlank(braveEnvApiA))
            braveApiSources.put("GPT_SEARCH_BRAVE_API_KEY", braveEnvApiA);
        if (!isBlank(braveEnvApiB))
            braveApiSources.put("BRAVE_API_KEY", braveEnvApiB);

        java.util.LinkedHashMap<String, String> braveSubSources = new java.util.LinkedHashMap<>();
        if (!isBlank(braveSubPrimary))
            braveSubSources.put("gpt-search.brave.subscription-token", braveSubPrimary);
        if (!isBlank(braveSubLegacy))
            braveSubSources.put("search.brave.subscription-token", braveSubLegacy);
        if (!isBlank(braveEnvSubC))
            braveSubSources.put("GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN", braveEnvSubC);
        if (!isBlank(braveEnvSubD))
            braveSubSources.put("BRAVE_SUBSCRIPTION_TOKEN", braveEnvSubD);

        // Diagnostics: source names only (never log secret values)
        java.util.LinkedHashMap<String, String> braveKeyPresent = new java.util.LinkedHashMap<>();
        braveKeyPresent.putAll(braveSubSources);
        braveKeyPresent.putAll(braveApiSources);

        try {
            if (!braveKeyPresent.isEmpty()) {
                alias.put("nova.provider.brave.key.sources", String.join(",", braveKeyPresent.keySet()));
                alias.put("nova.provider.brave.key.present.count", String.valueOf(braveKeyPresent.size()));
                alias.put("nova.provider.brave.key.duplicate",
                        String.valueOf(braveKeyPresent.size() > new java.util.HashSet<>(braveKeyPresent.keySet()).size()));
            }
        } catch (Exception ignore) {
            // best-effort only
        }

        // Distinct values per logical type (values redacted; used only for conflict heuristics)
        int braveSubDistinct = 0;
        {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            for (String v : braveSubSources.values()) {
                if (!isBlank(v))
                    set.add(v.trim());
            }
            braveSubDistinct = set.size();
        }
        int braveApiDistinct = 0;
        {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            for (String v : braveApiSources.values()) {
                if (!isBlank(v))
                    set.add(v.trim());
            }
            braveApiDistinct = set.size();
        }

        // Winner selection (subscription-token preferred; then api-key)
        String winner = null;
        String winnerSource = "";

        if (!isBlank(braveSubPrimary)) {
            winner = braveSubPrimary;
            winnerSource = "gpt-search.brave.subscription-token";
        } else if (!isBlank(braveApiPrimary)) {
            winner = braveApiPrimary;
            winnerSource = "gpt-search.brave.api-key";
        } else if (!isBlank(braveSubLegacy)) {
            winner = braveSubLegacy;
            winnerSource = "search.brave.subscription-token";
        } else if (!isBlank(braveApiLegacy)) {
            winner = braveApiLegacy;
            winnerSource = "search.brave.api-key";
        } else if (!isBlank(braveEnvSubC)) {
            winner = braveEnvSubC;
            winnerSource = "GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN";
        } else if (!isBlank(braveEnvApiA)) {
            winner = braveEnvApiA;
            winnerSource = "GPT_SEARCH_BRAVE_API_KEY";
        } else if (!isBlank(braveEnvSubD)) {
            winner = braveEnvSubD;
            winnerSource = "BRAVE_SUBSCRIPTION_TOKEN";
        } else if (!isBlank(braveEnvApiB)) {
            winner = braveEnvApiB;
            winnerSource = "BRAVE_API_KEY";
        }

        // Multi-token across groups is OK (do NOT disable).
        int braveUnionDistinct = 0;
        {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            for (String v : braveKeyPresent.values()) {
                if (!isBlank(v))
                    set.add(v.trim());
            }
            braveUnionDistinct = set.size();
        }
        boolean braveMultiToken = braveUnionDistinct > 1;

        // Hard conflict heuristics: multiple distinct values within the same logical type.
        boolean braveHardConflictInType = (braveSubDistinct > 1) || (braveApiDistinct > 1);

        // We no longer disable Brave on multi-token. Keep conflict=false to avoid "key_source_conflict" noise.
        alias.put("nova.provider.brave.key.conflict", "false");
        alias.put("nova.provider.brave.key.conflictResolved", String.valueOf(braveHardConflictInType));
        alias.put("nova.provider.brave.key.multi", String.valueOf(braveMultiToken));
        if (!isBlank(winnerSource)) {
            alias.put("nova.provider.brave.key.winnerSource", winnerSource);
        }

        if (braveHardConflictInType) {
            log.error(
                    "[NovaAlias] Multiple Brave keys detected within the SAME key-type group (subscriptionDistinct={}, apiDistinct={}). "
                            + "Will proceed with winnerSource={} by priority. (values redacted)",
                    braveSubDistinct, braveApiDistinct, winnerSource);
        } else if (braveMultiToken) {
            log.warn(
                    "[NovaAlias] Brave multi-token detected across groups: {} -> winnerSource={}. (values redacted)",
                    braveKeyPresent.keySet(), winnerSource);
        }

        if (!isBlank(winner)) {
            // Backfill canonical keys (only when blank) so downstream @Value resolution works reliably.
            putIfBlank(env, alias, "gpt-search.brave.subscription-token", winner);
            putIfBlank(env, alias, "gpt-search.brave.api-key", winner);
            putIfBlank(env, alias, "search.brave.subscription-token", winner);
            putIfBlank(env, alias, "search.brave.api-key", winner);

            // Also backfill ENV aliases when they are blank (best-effort).
            putIfBlank(env, alias, "GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN", winner);
            putIfBlank(env, alias, "GPT_SEARCH_BRAVE_API_KEY", winner);
            putIfBlank(env, alias, "BRAVE_SUBSCRIPTION_TOKEN", winner);
            putIfBlank(env, alias, "BRAVE_API_KEY", winner);
        }

        if (alias.isEmpty()) {
            return;
        }

        // Run after config data load so we can see what actually resolved, but insert
        // the
        // alias property source with HIGH precedence so it can override blank
        // placeholder
        // values (e.g. "${OPENAI_API_KEY:}" resolving to empty string).
        MutablePropertySources sources = env.getPropertySources();
        if (sources.contains(ALIAS_SOURCE_NAME)) {
            sources.remove(ALIAS_SOURCE_NAME);
        }
        sources.addFirst(new MapPropertySource(ALIAS_SOURCE_NAME, alias));

        // Do NOT log values (may contain secrets).
        log.info("[NovaAlias] Applied {} property alias(es) to reduce silent misconfiguration.", alias.size());
        log.debug("[NovaAlias] Aliased keys: {}", alias.keySet());
    }

    @Override
    public int getOrder() {
        // Run late (after config data load) so aliases are computed from the final
        // environment.
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Returns a property value only if it comes from a non-SystemEnvironment
     * property source.
     *
     * <p>
     * Spring's relaxed binding can expose a single environment variable under
     * multiple keys
     * (e.g., GPT_SEARCH_BRAVE_API_KEY → gpt-search.brave.api-key). For
     * "single-source" validation,
     * we must distinguish explicit property configuration from ENV-derived aliases.
     * </p>
     */
    private static String getNonEnvProperty(ConfigurableEnvironment env, String key) {
        if (env == null || isBlank(key)) {
            return null;
        }
        try {
            for (PropertySource<?> ps : env.getPropertySources()) {
                if (ps == null)
                    continue;
                // Skip our own alias source (we are computing it now).
                if (ALIAS_SOURCE_NAME.equals(ps.getName()))
                    continue;
                // Skip SystemEnvironment to avoid double-counting ENV via relaxed names.
                if (ps instanceof SystemEnvironmentPropertySource)
                    continue;

                Object v = ps.getProperty(key);
                if (v == null)
                    continue;
                String s = String.valueOf(v);
                if (isBlank(s))
                    continue;
                // Treat placeholders as absent (we'll resolve via winner).
                if (s.contains("${") && s.contains("}"))
                    continue;
                return s;
            }
        } catch (Exception ignore) {
            // non-fatal
        }
        return null;
    }

    private static void putIfBlank(ConfigurableEnvironment env, Map<String, Object> out, String targetKey,
            String value) {
        if (isBlank(targetKey) || isBlank(value)) {
            return;
        }
        String current = env.getProperty(targetKey);
        if (isBlank(current)) {
            out.put(targetKey, value);
        }
    }

    /**
     * Force a property to be seen as blank by adding a high-precedence override.
     *
     * <p>
     * Used to satisfy strict downstream validators (e.g., KeyResolver) that
     * require only ONE key among a known set to be visible as non-blank.
     * </p>
     */
    private static void forceBlankIfPresent(
            ConfigurableEnvironment env,
            Map<String, Object> out,
            String key,
            String winnerKey) {
        if (env == null || out == null || isBlank(key) || key.equals(winnerKey)) {
            return;
        }
        try {
            String current = env.getProperty(key);
            if (!isBlank(current)) {
                out.put(key, "");
            }
        } catch (Exception ignore) {
            // non-fatal
        }
    }

    /**
     * Normalizes a potential secret and treats obvious placeholders as missing.
     */
    private static String normalizeNonPlaceholder(String raw) {
        String n = normalizeSecret(raw);
        if (n == null) {
            return null;
        }
        return looksLikePlaceholder(n) ? null : n;
    }

    private static String firstNonBlankFromEnv(ConfigurableEnvironment env, String... keys) {
        if (env == null || keys == null) {
            return null;
        }
        for (String k : keys) {
            if (isBlank(k)) {
                continue;
            }
            String v;
            try {
                v = env.getProperty(k);
            } catch (Exception ignore) {
                v = null;
            }
            if (!isBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static void warnIfConflicting(ConfigurableEnvironment env, String keyA, String keyB) {
        if (env == null || isBlank(keyA) || isBlank(keyB)) {
            return;
        }
        try {
            String a = env.getProperty(keyA);
            String b = env.getProperty(keyB);
            if (isBlank(a) || isBlank(b)) {
                return;
            }
            // We must never log actual secret values.
            if (!a.trim().equals(b.trim())) {
                log.warn(
                        "[NovaAlias] Conflicting values detected for '{}' and '{}'. Set only one (values are redacted).",
                        keyA, keyB);
            }
        } catch (Exception ignore) {
            // non-fatal
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String reconcileOpenAiKeyConflict(
            ConfigurableEnvironment env,
            Map<String, Object> out,
            String keyA,
            String keyB,
            String... alsoAlignKeys) {
        if (env == null || out == null || isBlank(keyA) || isBlank(keyB)) {
            return null;
        }

        // NOTE: Some operators set env vars with surrounding quotes ("sk-..."), which
        // should be treated
        // as the same secret but will still break strict equality checks downstream.
        String rawA = rawTrimToNull(env.getProperty(keyA));
        String rawB = rawTrimToNull(env.getProperty(keyB));
        if (rawA == null || rawB == null) {
            return null;
        }

        String a = normalizeSecret(rawA);
        String b = normalizeSecret(rawB);
        if (a == null || b == null) {
            return null;
        }

        // If normalized secrets match but raw values differ (quotes, whitespace), we
        // still override
        // both keys to the normalized winner to prevent strict downstream validators
        // from failing.
        boolean normalizedSame = a.equals(b);
        boolean rawSame = rawA.equals(rawB);
        if (normalizedSame && rawSame) {
            return null;
        }
        if (normalizedSame) {
            out.put(keyA, a);
            out.put(keyB, a);
            if (alsoAlignKeys != null) {
                for (String k : alsoAlignKeys) {
                    if (!isBlank(k)) {
                        out.put(k, a);
                    }
                }
            }
            log.warn(
                    "[NovaAlias] OpenAI API key appears duplicated with different formatting for '{}' and '{}'. " +
                            "Overriding both to a normalized value (values are redacted).",
                    keyA, keyB);
            return a;
        }

        ProviderHint hint = detectProviderHint(env);
        int scoreA = scoreApiKeyForHint(a, hint);
        int scoreB = scoreApiKeyForHint(b, hint);

        String winnerKeyName;
        String winnerValue;
        if (scoreB > scoreA) {
            winnerKeyName = keyB;
            winnerValue = b;
        } else {
            // Tie-breaker (or A wins): prefer the flat key used in our docs.
            winnerKeyName = keyA;
            winnerValue = a;
        }

        // Force both keys to the winner value so downstream strict validation won't
        // hard-fail.
        out.put(keyA, winnerValue);
        out.put(keyB, winnerValue);
        if (alsoAlignKeys != null) {
            for (String k : alsoAlignKeys) {
                if (!isBlank(k)) {
                    out.put(k, winnerValue);
                }
            }
        }

        // Never log secret values.
        log.error(
                "[NovaAlias] Conflicting OpenAI API keys detected for '{}' and '{}'. " +
                        "To avoid runtime failures, overriding both to follow '{}' (values are redacted). " +
                        "Please set only ONE of these keys.",
                keyA, keyB, winnerKeyName);

        return winnerValue;
    }

    private enum ProviderHint {
        OPENAI, GROQ, AZURE, UNKNOWN
    }

    private static ProviderHint detectProviderHint(ConfigurableEnvironment env) {
        String baseUrl = firstNonBlankFromEnv(env,
                "llm.base-url-openai",
                "llm.openai.base-url",
                "openai.base-url",
                "spring.ai.openai.base-url",
                "OPENAI_BASE_URL");
        if (isBlank(baseUrl)) {
            return ProviderHint.UNKNOWN;
        }
        String u = baseUrl.trim().toLowerCase(Locale.ROOT);
        if (u.contains("api.openai.com")) {
            return ProviderHint.OPENAI;
        }
        if (u.contains("groq")) {
            return ProviderHint.GROQ;
        }
        if (u.contains("azure")) {
            return ProviderHint.AZURE;
        }
        return ProviderHint.UNKNOWN;
    }

    private static int scoreApiKeyForHint(String rawKey, ProviderHint hint) {
        String k = normalizeSecret(rawKey);
        if (k == null) {
            return Integer.MIN_VALUE;
        }

        // Strong penalty for obvious placeholders.
        if (looksLikePlaceholder(k)) {
            return -100;
        }

        int score = 0;
        // Generic signal: real keys are usually longer.
        if (k.length() >= 20) {
            score += 2;
        }

        switch (hint) {
            case OPENAI -> {
                if (looksLikeOpenAiKey(k))
                    score += 20;
                if (looksLikeGroqKey(k))
                    score -= 20;
            }
            case GROQ -> {
                if (looksLikeGroqKey(k))
                    score += 20;
                if (looksLikeOpenAiKey(k))
                    score -= 5; // still acceptable for some gateways, but deprioritize
            }
            case AZURE, UNKNOWN -> {
                // No strong prefix expectations.
                if (looksLikeOpenAiKey(k))
                    score += 5;
                if (looksLikeGroqKey(k))
                    score += 5;
            }
        }

        return score;
    }

    private static String rawTrimToNull(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Normalizes secrets coming from env/config:
     * - trims whitespace
     * - strips a single pair of surrounding quotes ("..." or '...')
     */
    private static String normalizeSecret(String s) {
        String t = rawTrimToNull(s);
        if (t == null)
            return null;

        // Strip a single surrounding quote pair.
        if (t.length() >= 2) {
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                String inner = t.substring(1, t.length() - 1).trim();
                t = inner.isEmpty() ? t : inner;
            }
        }

        return t.isEmpty() ? null : t;
    }

    private static boolean looksLikePlaceholder(String v) {
        if (isBlank(v))
            return true;
        String s = v.trim();

        // If config placeholder leaked through (e.g., "${OPENAI_API_KEY:}") treat as
        // placeholder.
        if (s.contains("${") && s.contains("}")) {
            return true;
        }

        String lc = s.toLowerCase(Locale.ROOT);
        if ("dummy".equals(lc) || "changeme".equals(lc) || "example".equals(lc) || "test".equals(lc)) {
            return true;
        }
        if ("none".equals(lc) || "null".equals(lc) || "undefined".equals(lc)) {
            return true;
        }
        // Common local placeholders in this repo.
        if ("sk-local".equals(lc) || lc.startsWith("sk-local-")) {
            return true;
        }

        // Very short keys are almost certainly placeholders.
        return s.length() < 10;
    }

    private static boolean looksLikeOpenAiKey(String v) {
        if (isBlank(v))
            return false;
        String s = v.trim();
        if (s.startsWith("sk-") || s.startsWith("sk-proj-")) {
            // Exclude our local placeholder.
            return !s.equalsIgnoreCase("sk-local") && !s.toLowerCase(Locale.ROOT).startsWith("sk-local-");
        }
        return false;
    }

    private static boolean looksLikeGroqKey(String v) {
        if (isBlank(v))
            return false;
        return v.trim().startsWith("gsk_");
    }
}
