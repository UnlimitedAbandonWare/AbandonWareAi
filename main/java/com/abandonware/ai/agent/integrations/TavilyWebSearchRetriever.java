
package com.abandonware.ai.agent.integrations;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;




/**
 * Minimal Tavily web search client returning the standard RAG result schema.
 */
public class TavilyWebSearchRetriever {

    private final HttpClient client;
    private final ObjectMapper om = new ObjectMapper();
    private final String apiUrl;
    private final Supplier<String> apiKeySupplier;
    private final ProviderCredentialResolver credentialResolver;

    public TavilyWebSearchRetriever() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
                "https://api.tavily.com/search",
                () -> null);
    }

    public TavilyWebSearchRetriever(ProviderCredentialResolver credentialResolver) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
                "https://api.tavily.com/search",
                credentialResolver);
    }

    TavilyWebSearchRetriever(HttpClient client, String apiUrl, Supplier<String> apiKeySupplier) {
        this.client = Objects.requireNonNull(client, "client");
        this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
        this.credentialResolver = null;
    }

    TavilyWebSearchRetriever(
            HttpClient client,
            String apiUrl,
            ProviderCredentialResolver credentialResolver) {
        this.client = Objects.requireNonNull(client, "client");
        this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
        this.apiKeySupplier = () -> null;
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver");
    }

    public boolean isEnabled(String domain) {
        ResolvedCredential credential = resolveCredential();
        if (!credential.enabled()) return false;
        if (domain == null) return false;
        String d = domain.toLowerCase(Locale.ROOT);
        return d.equals("web") || d.equals("web+local") || d.equals("rrf");
    }

    public List<Map<String,Object>> search(String query, int topK, String domain) {
        ResolvedCredential credential = resolveCredential();
        if (!credential.enabled()) {
            traceProviderDisabled(query, topK, domain, credential.disabledReason());
            return List.of();
        }
        if (!isDomainEnabled(domain)) {
            traceDomainSkipped(query, topK, domain, "domain_not_enabled");
            return List.of();
        }
        try {
            String key = credential.value();
            String lang = containsHangul(query) ? "ko" : "en";
            Map<String,Object> payload = new HashMap<>();
            payload.put("api_key", key);
            payload.put("query", query);
            payload.put("max_results", Math.max(3, Math.min(topK * 2, 10)));
            payload.put("search_depth", "advanced");
            payload.put("include_answer", false);
            payload.put("include_domains", Collections.emptyList());
            payload.put("exclude_domains", Collections.emptyList());
            payload.put("language", lang);

            String json = om.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                traceHttpFailure(query, topK, domain, resp.statusCode(), resp.body());
                return List.of();
            }
            JsonNode root = om.readTree(resp.body());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                traceProviderEmpty(query, topK, domain, resp.body());
                return List.of();
            }
            List<Map<String,Object>> out = new ArrayList<>();
            int r = 1;
            for (JsonNode item : results) {
                String title = text(item, "title");
                String content = text(item, "content");
                String url = text(item, "url");
                String id = url != null ? url : TextUtils.sha1((title == null ? "" : title) + "||" + (content == null ? "" : content));
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("title", title == null ? url : title);
                m.put("snippet", content == null ? "" : content);
                m.put("source", url);
                m.put("score", 1.0 / Math.max(1, r));
                m.put("rank", r);
                out.add(m);
                r++;
            }
            if (out.isEmpty()) {
                traceProviderEmpty(query, topK, domain, resp.body());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            traceSuppressed("search.interrupted", query, topK, domain, e);
            return List.of();
        } catch (IOException | RuntimeException e) {
            traceSuppressed("search", query, topK, domain, e);
            return List.of();
        }
    }

    private ResolvedCredential resolveCredential() {
        ProviderCredentialResolver resolver = credentialResolver;
        if (resolver != null) {
            ProviderCredentialResolver.Resolution resolution = resolver.resolve(
                    ProviderCredentialResolver.Provider.TAVILY);
            if (!resolution.enabled()) {
                String reason = "missing-credential".equals(resolution.disabledReason())
                        ? "missing_tavily_api_key"
                        : resolution.disabledReason();
                return new ResolvedCredential(null, false, reason);
            }
            return new ResolvedCredential(resolution.valueOrNull(), true, "");
        }
        String value = apiKeySupplier.get();
        if (ConfigValueGuards.isMissing(value)) {
            return new ResolvedCredential(null, false, "missing_tavily_api_key");
        }
        return new ResolvedCredential(value, true, "");
    }

    private static boolean isDomainEnabled(String domain) {
        if (domain == null) {
            return false;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        return normalized.equals("web") || normalized.equals("web+local") || normalized.equals("rrf");
    }

    private record ResolvedCredential(String value, boolean enabled, String disabledReason) {
    }

    private static boolean containsHangul(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.HANGUL_JAMO
                    || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private void traceProviderDisabled(String query, int topK, String domain, String reason) {
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.disabledReason", safeReason);
        TraceStore.put("web.tavily.disabledReasonCanonical", safeReason);
        TraceStore.put("web.tavily.skipped", true);
        TraceStore.put("web.tavily.skipped.reason", safeReason);
        TraceStore.put("web.tavily.failureReason", "provider-disabled");
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.providerEmpty", false);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("web.tavily.queryLength", query == null ? 0 : query.length());
        TraceStore.put("web.tavily.requestedCount", Math.max(3, Math.min(topK * 2, 10)));
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("web.tavily.domainPolicy", SafeRedactor.traceLabelOrFallback(domain, "unknown"));
    }

    private void traceDomainSkipped(String query, int topK, String domain, String reason) {
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        TraceStore.put("web.tavily.providerDisabled", false);
        TraceStore.put("web.tavily.skipped", true);
        TraceStore.put("web.tavily.skipped.reason", safeReason);
        TraceStore.put("web.tavily.failureReason", "domain-disabled");
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.providerEmpty", false);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("web.tavily.queryLength", query == null ? 0 : query.length());
        TraceStore.put("web.tavily.requestedCount", Math.max(3, Math.min(topK * 2, 10)));
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("web.tavily.domainPolicy", SafeRedactor.traceLabelOrFallback(domain, "unknown"));
    }

    private void traceProviderEmpty(String query, int topK, String domain, String body) {
        TraceStore.put("web.tavily.providerEmpty", true);
        TraceStore.put("web.tavily.failureReason", "provider-empty");
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.rateLimited", false);
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("web.tavily.queryLength", query == null ? 0 : query.length());
        TraceStore.put("web.tavily.requestedCount", Math.max(3, Math.min(topK * 2, 10)));
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("web.tavily.bodyHash", SafeRedactor.hashValue(body));
        TraceStore.put("web.tavily.bodyLength", body == null ? 0 : body.length());
        TraceStore.put("web.tavily.domainPolicy", SafeRedactor.traceLabelOrFallback(domain, "unknown"));
    }

    private void traceHttpFailure(String query, int topK, String domain, int statusCode, String body) {
        boolean rateLimited = statusCode == 429;
        String reason = rateLimited ? "rate-limit" : "http-error";
        TraceStore.put("web.tavily.skipped", true);
        TraceStore.put("web.tavily.skipped.reason", reason);
        TraceStore.put("web.tavily.failureReason", reason);
        TraceStore.put("web.tavily.httpStatus", statusCode);
        TraceStore.put("web.tavily.rateLimited", rateLimited);
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.providerEmpty", false);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("web.tavily.queryLength", query == null ? 0 : query.length());
        TraceStore.put("web.tavily.requestedCount", Math.max(3, Math.min(topK * 2, 10)));
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("web.tavily.bodyHash", SafeRedactor.hashValue(body));
        TraceStore.put("web.tavily.bodyLength", body == null ? 0 : body.length());
        TraceStore.put("web.tavily.domainPolicy", SafeRedactor.traceLabelOrFallback(domain, "unknown"));
        if (rateLimited) {
            TraceStore.put("web.rateLimited", true);
        }
    }

    private void traceSuppressed(String stage, String query, int topK, String domain, Throwable error) {
        String reason = failureReason(error);
        TraceStore.put("agent.tavily.suppressed", true);
        TraceStore.put("agent.tavily.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.tavily.suppressed.errorType", errorType(error));
        TraceStore.put("agent.tavily.suppressed.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("agent.tavily.suppressed.queryLength", query == null ? 0 : query.length());
        TraceStore.put("agent.tavily.suppressed.endpointHash", SafeRedactor.hashValue(apiUrl));
        TraceStore.put("agent.tavily.suppressed.endpointLength", apiUrl == null ? 0 : apiUrl.length());
        TraceStore.put("web.tavily.failureReason", reason);
        TraceStore.put("web.tavily.skipped.reason", reason);
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("web.tavily.queryLength", query == null ? 0 : query.length());
        TraceStore.put("web.tavily.requestedCount", Math.max(3, Math.min(topK * 2, 10)));
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("web.tavily.domainPolicy", SafeRedactor.traceLabelOrFallback(domain, "unknown"));
    }

    private static String failureReason(Throwable error) {
        return error instanceof InterruptedException ? "cancelled" : "exception";
    }

    private static String errorType(Throwable error) {
        if (error instanceof InterruptedException) {
            return "cancelled";
        }
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
