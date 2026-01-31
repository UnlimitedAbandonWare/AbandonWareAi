package ai.abandonware.nova.orch.llm;

import java.util.List;

import com.example.lms.llm.ModelCapabilities;

/**
 * Shared helpers for model guard logic (canonicalization, prefix matching,
 * baseUrl checks).
 */
public final class ModelGuardSupport {

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
        for (String p : responsesOnlyPrefixes) {
            if (p == null || p.isBlank()) {
                continue;
            }
            String pp = p.trim();
            if (canon.equals(pp) || canon.startsWith(pp + "-")) {
                return true;
            }
        }
        return false;
    }

    public static boolean looksLikeOpenAiBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        String lc = baseUrl.toLowerCase();
        // Accept both https://api.openai.com and potential enterprise base hosts
        // containing openai.com
        return lc.contains("api.openai.com") || (lc.contains("openai.com") && lc.contains("/v1"));
    }

    public static String buildExpectedFailureMessage(String requestedModel, String endpoint, String mode) {
        // Disable.txt style: keep this short but structured.
        String m = (requestedModel == null) ? "" : requestedModel;
        return ""
                + "code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH\n"
                + "requestedModel: " + m + "\n"
                + "endpoint: " + endpoint + "\n"
                + "reason: 모델-엔드포인트 호환 불가(Responses 성향 모델 → Chat Completions 호출)\n"
                + "actionTaken: " + mode + "\n"
                + "hint: /v1/responses 라우팅 또는 chat용 모델로 치환 설정(nova.orch.model-guard)\n";
    }
}
