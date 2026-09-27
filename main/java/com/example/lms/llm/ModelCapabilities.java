package com.example.lms.llm;

import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

/**
 * 모델별 파라미터 제약(temperature/top_p/penalties 등)을 중앙화.
 *
 * <p>
 * OpenAI GPT-5.* / O-series는 temperature 기본값(1.0)만 허용하는 경우가 있어,
 * non-default 값을 보내면 400(unsupported_value)로 거절될 수 있다.
 * </p>
 */
public final class ModelCapabilities {

    private static final Logger log = LoggerFactory.getLogger(ModelCapabilities.class);

    public static final String DEFAULT_LOCAL_CHAT_MODEL = "gemma4:26b";
    public static final String DEFAULT_LOCAL_FAST_MODEL = "qwen3.5:9b";
    public static final String DEFAULT_LOCAL_JUDGE_MODEL = "smtek/Qwen3.8-27B:Q3_K_XL";
    public static final String DEFAULT_LOCAL_CODER_MODEL = "smtek/Qwen3.8-27B:Q3_K_XL";
    public static final String DEFAULT_LOCAL_VISION_MODEL = "qwen3-vl:8b";
    public static final String DEFAULT_LOCAL_EMBEDDING_MODEL = "qwen3-embedding:4b";
    public static final String OPTIONAL_LOCAL_CHAT_MODEL = "gemma4:31b-it-q4_K_M";

    /** 기본값(1.0)만 허용하는 모델(exact match) */
    private static final Set<String> RIGID_TEMPERATURE_MODELS = Set.of(
            "qwen2.5-7b-instruct",
            "gpt-5.5");

    /** prefix 기반 rigid 제약 (소문자 기준) */
    private static final Set<String> RIGID_SAMPLING_PREFIXES = Set.of(
            "gpt-5", // OpenAI GPT-5 family permits only default sampling values.
            "o1", // o-series reasoning 모델
            "o3",
            "o4");

    private static final Set<String> PLACEHOLDER_MODEL_IDS = Set.of(
            "model", "unknown", "placeholder");

	    // 오케스트레이션 suffix 태그들 (로컬 모델 tag는 제외)
	    private static final Set<String> ORCH_TAGS = Set.of(
	            "fallback", "evidence", "aux", "draft", "probe", "mini", "fast", "high", "low", "debug");

    private ModelCapabilities() {
    }

    /**
     * [추가] 모델 ID를 정규화하여 반환합니다. (예: "model:fallback" -> "model")
     */
    public static String canonicalModelName(String modelId) {
	        if (modelId == null) {
	            return null;
	        }
	        String m = modelId.trim();

	        // "lc:" prefix 먼저 제거
	        if (m.regionMatches(true, 0, "lc:", 0, 3)) {
	            m = m.substring(3).trim();
	        }

	        // 오케스트레이션 태그만 제거 (로컬 모델 태그는 보존)
	        while (true) {
	            int colon = m.lastIndexOf(':');
	            if (colon <= 0 || colon == m.length() - 1) {
	                return m;
	            }
	            String tag = m.substring(colon + 1).toLowerCase(Locale.ROOT);
	            if (!ORCH_TAGS.contains(tag)) {
	                return m;
	            }
	            m = m.substring(0, colon);
	        }
    }

    public static String normalizeLocalModelAlias(String modelId) {
        String m = canonicalModelName(modelId);
        if (m == null || m.isBlank()) {
            return DEFAULT_LOCAL_CHAT_MODEL;
        }
        String lower = m.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("gemma4-26b")) {
            return "gemma4:26b";
        }
        return m.trim();
    }

    /**
     * 해당 모델이 temperature 기본값(1.0)만 허용하는지 판단.
     * - exact match (RIGID_TEMPERATURE_MODELS)
     * - prefix match (RIGID_SAMPLING_PREFIXES)
     */
    public static boolean requiresDefaultTemperature(String modelId) {
        if (modelId == null)
            return false;

        String m = modelId.trim().toLowerCase(Locale.ROOT);

        // "gpt-5.5:fallback:evidence" 같은 태그 처리
        int colon = m.indexOf(':');
        if (colon > 0) {
            m = m.substring(0, colon);
        }

        // exact match
        if (RIGID_TEMPERATURE_MODELS.contains(m))
            return true;

        // prefix match
        for (String p : RIGID_SAMPLING_PREFIXES) {
            if (m.startsWith(p))
                return true;
        }

        // legacy guard
        return m.contains("qwen2.5-7b-instruct");
    }

    /**
     * top_p도 기본값(1.0)만 허용하는지 판단.
     * (현재는 temperature와 동일 규칙 적용)
     */
    public static boolean requiresDefaultTopP(String modelId) {
        return hasRigidSamplingPrefix(modelId);
    }

    /** penalties(frequency/presence)도 기본값(0.0)만 허용하는지 판단. */
    public static boolean requiresDefaultPenalties(String modelId) {
        return hasRigidSamplingPrefix(modelId);
    }

    public static boolean isRemoteLookingModelId(String modelId) {
        String m = canonicalModelName(modelId);
        if (m == null || m.isBlank()) {
            return false;
        }
        m = m.trim().toLowerCase(Locale.ROOT);
        return m.startsWith("gpt-") || m.startsWith("openai") || m.matches("o\\d.*") || m.startsWith("o-");
    }

    public static boolean isPlaceholderModelId(String modelId) {
        String m = canonicalModelName(modelId);
        if (m == null || m.isBlank()) {
            return false;
        }
        m = m.trim().toLowerCase(Locale.ROOT);
        return PLACEHOLDER_MODEL_IDS.contains(m) || (m.startsWith("${") && m.endsWith("}"));
    }

    public static boolean isLocalChatModelId(String modelId) {
        String m = canonicalModelName(modelId);
        if (m == null || m.isBlank()) {
            return false;
        }
        m = m.trim().toLowerCase(Locale.ROOT);
        if (isPlaceholderModelId(m)) {
            return false;
        }
        if (m.contains("embedding") || m.startsWith("text-embedding")) {
            return false;
        }
        if ("babbage-002".equals(m) || "davinci-002".equals(m)) {
            return false;
        }
        return !isRemoteLookingModelId(m);
    }

    private static boolean hasRigidSamplingPrefix(String modelId) {
        if (modelId == null)
            return false;
        String m = modelId.trim().toLowerCase(Locale.ROOT);
        int colon = m.indexOf(':');
        if (colon > 0) {
            m = m.substring(0, colon);
        }
        for (String p : RIGID_SAMPLING_PREFIXES) {
            if (m.startsWith(p))
                return true;
        }
        return false;
    }

    /** 모델이 rigid면 1.0으로 강제 */
    public static double sanitizeTemperature(String modelId, double requested) {
        if (requiresDefaultTemperature(modelId)) {
            if (Math.abs(requested - 1.0d) > 1e-9) {
                log.debug("[ThinkingSetup] Clamping temperature {} -> 1.0 for rigid-temp modelHash={} modelLength={}",
                        requested, SafeRedactor.hashValue(modelId), lengthOf(modelId));
            }
            return 1.0d;
        }
        // 범위 클램프 (0.0 ~ 2.0)
        return clamp(requested, 0.0d, 2.0d);
    }

    /** Nullable 버전 */
    public static Double sanitizeTemperature(String modelId, Double requested) {
        if (requested == null)
            return null;
        return sanitizeTemperature(modelId, requested.doubleValue());
    }

    /** top_p 범위 클램프(0~1). rigid면 1.0 */
    public static Double sanitizeTopP(String modelId, Double requested) {
        if (requested == null)
            return null;
        if (requiresDefaultTopP(modelId))
            return 1.0d;
        return clamp(requested, 0.0d, 1.0d);
    }

    /** penalties 범위 클램프(-2~2). rigid면 0.0 */
    public static Double sanitizeFrequencyPenalty(String modelId, Double requested) {
        if (requested == null)
            return null;
        if (requiresDefaultPenalties(modelId))
            return 0.0d;
        return clamp(requested, -2.0d, 2.0d);
    }

    public static Double sanitizePresencePenalty(String modelId, Double requested) {
        if (requested == null)
            return null;
        if (requiresDefaultPenalties(modelId))
            return 0.0d;
        return clamp(requested, -2.0d, 2.0d);
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v))
            return 1.0d;
        if (v < min)
            return min;
        if (v > max)
            return max;
        return v;
    }

    private static Double clamp(Double v, double min, double max) {
        if (v == null)
            return null;
        return clamp(v.doubleValue(), min, max);
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }
}
