package ai.abandonware.nova.orch.llm;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.net.URI;

import com.example.lms.llm.ModelCapabilities;
import com.example.lms.trace.SafeRedactor;

/**
 * Shared helpers for model guard logic (canonicalization, prefix matching,
 * baseUrl checks).
 */
public final class ModelGuardSupport {

    private static final Set<String> ALLOWED_EXPECTED_FAILURE_ENDPOINTS = Set.of(
            "/v1/chat/completions",
            "/v1/responses",
            "/v1/embeddings");

    private ModelGuardSupport() {
    }

    public static String canonicalModelName(String modelName) {
        return ModelCapabilities.canonicalModelName(modelName);
    }

    public static boolean isResponsesOnlyModel(String modelName, List<String> responsesOnlyPrefixes) {
        if (modelName == null) {
            return false;
        }
        String canon = canonicalModelName(modelName);
        if (responsesOnlyPrefixes == null || responsesOnlyPrefixes.isEmpty()) {
            return false;
        }
        String normalizedCanon = canon.trim().toLowerCase(Locale.ROOT);
        for (String p : responsesOnlyPrefixes) {
            if (p == null || p.isBlank()) {
                continue;
            }
            String pp = p.trim().toLowerCase(Locale.ROOT);
            // API exclusivity does not extend to unverified variants or snapshots.
            if (normalizedCanon.equals(pp)) {
                return true;
            }
        }
        return false;
    }

    public static boolean looksLikeOpenAiBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "api.openai.com".equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String buildExpectedFailureMessage(String requestedModel, String endpoint, String mode) {
        String canonical = (requestedModel == null) ? "" : canonicalModelName(requestedModel);
        String m = SafeRedactor.safeMessage(canonical, 96);
        if (m == null) {
            m = "";
        }
        return ""
                + "code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH\n"
                + "requestedModel: " + m + "\n"
                + "endpoint: " + safeExpectedFailureEndpoint(endpoint) + "\n"
                + "reason: responses-only model cannot be used on chat-completions endpoint\n"
                + "actionTaken: " + mode + "\n"
                + "hint: route this model through /v1/responses or configure a chat-compatible substitute via nova.orch.model-guard\n";
    }

    private static String safeExpectedFailureEndpoint(String endpoint) {
        if (endpoint == null) {
            return "<REDACTED>";
        }
        String normalized = endpoint.trim();
        return ALLOWED_EXPECTED_FAILURE_ENDPOINTS.contains(normalized) ? normalized : "<REDACTED>";
    }
}
