package com.example.lms.llm;

import java.util.Locale;
import java.util.Map;

/**
 * OpenAI API 토큰 파라미터 호환 헬퍼.
 *
 * <p>일부 최신 OpenAI 모델(gpt-5.*, o1, o3, o4)은 legacy {@code max_tokens}를 거부하고
 * {@code max_completion_tokens}를 요구함. LangChain4j 1.0.1은 {@code maxCompletionTokens}
 * 빌더 메서드를 지원하며, 해당 모델에서는 {@code max_completion_tokens}를
 * 전송한다(호출: LlmRouterAspect, GeminiGateway).
 *
 * <p>추가: 프록시/게이트웨이 환경에서도 모델별 규칙을 바꿀 수 있도록
 * {@link OpenAiModelParamMatrix}를 (선택적으로) 연동한다.
 */
public final class OpenAiTokenParamCompat {

    private static volatile OpenAiModelParamMatrix MATRIX;

    private OpenAiTokenParamCompat() {}

    /** (Optional) config-driven matrix register (called by {@link OpenAiModelParamMatrix}). */
    public static void registerMatrix(OpenAiModelParamMatrix matrix) {
        MATRIX = matrix;
    }

    /** GPT-5 계열 여부 판단 */
    public static boolean isGpt5Family(String modelName) {
        if (modelName == null) return false;
        String s = modelName.trim().toLowerCase();
        return s.startsWith("gpt-5");
    }

    /** o-series (reasoning) 모델 여부 판단 */
    public static boolean isOSeriesModel(String modelName) {
        if (modelName == null) return false;
        String s = modelName.trim().toLowerCase();
        return s.startsWith("o1") || s.startsWith("o3") || s.startsWith("o4");
    }

    /** max_completion_tokens가 필요한 모델인지 판단(휴리스틱) */
    public static boolean usesMaxCompletionTokens(String modelName) {
        return isGpt5Family(modelName) || isOSeriesModel(modelName);
    }

    /** 공식 OpenAI 엔드포인트 여부 */
    public static boolean isOfficialOpenAi(String baseUrl) {
        String base = normalizeBaseUrl(baseUrl);
        return base.contains("api.openai.com");
    }

    /**
     * max_completion_tokens를 사용해야 하는지 판단.
     *
     * <p>우선순위:
     * <ol>
     *   <li>{@link OpenAiModelParamMatrix}가 주입되어 있으면 그 규칙을 우선 적용</li>
     *   <li>없으면: GPT-5/o-series + 공식 OpenAI 조합일 때 true</li>
     * </ol>
     */
    public static boolean shouldUseMaxCompletionTokens(String modelName, String baseUrl) {
        OpenAiModelParamMatrix m = MATRIX;
        if (m != null) {
            return m.resolveTokenParam(modelName, baseUrl) == OpenAiModelParamMatrix.TokenParam.MAX_COMPLETION_TOKENS;
        }
        return usesMaxCompletionTokens(modelName) && isOfficialOpenAi(baseUrl);
    }

    /**
     * WebClient payload용 토큰 파라미터 키 반환.
     *
     * @return "max_tokens" | "max_completion_tokens" | null(omit)
     */
    public static String tokenParamKey(String modelName, String baseUrl) {
        OpenAiModelParamMatrix m = MATRIX;
        if (m != null) {
            return m.tokenParamKey(modelName, baseUrl);
        }
        return shouldUseMaxCompletionTokens(modelName, baseUrl)
                ? "max_completion_tokens"
                : "max_tokens";
    }

    /**
     * LangChain4j builder용: legacy maxTokens를 보내도 되는지 판단.
     *
     * <p>matrix가 MAX_TOKENS이면 true; MAX_COMPLETION_TOKENS/NONE이면 false
     * (해당 모델에서는 {@code max_completion_tokens} 전송 또는 파라미터 생략).
     */
    public static boolean shouldSendLegacyMaxTokens(String modelName, String baseUrl) {
        OpenAiModelParamMatrix m = MATRIX;
        if (m != null) {
            return m.resolveTokenParam(modelName, baseUrl) == OpenAiModelParamMatrix.TokenParam.MAX_TOKENS;
        }
        return !shouldUseMaxCompletionTokens(modelName, baseUrl);
    }

    /** 재시도용 토큰 파라미터 키 교체 */
    public static void replaceTokenParamForRetry(Map<String, Object> payload, Object value) {
        if (payload == null) return;
        boolean hadMaxTokens = payload.containsKey("max_tokens");
        boolean hadMaxCompletionTokens = payload.containsKey("max_completion_tokens");
        if (hadMaxTokens) {
            payload.remove("max_tokens");
            payload.put("max_completion_tokens", value);
            return;
        }
        if (hadMaxCompletionTokens) {
            payload.remove("max_completion_tokens");
            payload.put("max_tokens", value);
            return;
        }
        payload.put("max_completion_tokens", value);
    }

    /**
     * unsupported_parameter(max_tokens) 에러 감지.
     * Exception chain을 순회하며 에러 메시지 패턴 매칭.
     */
    public static boolean isUnsupportedMaxTokens(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            String msg = cur.getMessage();
            if (msg != null) {
                String s = msg.toLowerCase(Locale.ROOT);
                if (s.contains("unsupported parameter") && s.contains("max_tokens")) {
                    return true;
                }
                if (s.contains("unknown parameter") && s.contains("max_tokens")) {
                    return true;
                }
                if (s.contains("invalid_request_error") && s.contains("max_tokens")) {
                    return true;
                }
                if (s.contains("max_completion_tokens") && s.contains("max_tokens") && s.contains("use")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * unsupported sampling 파라미터(temperature/top_p/penalties) 에러 감지.
     */
    public static boolean isUnsupportedSampling(Throwable t) {
        return isUnsupportedTemperature(t) || isUnsupportedTopP(t) || isUnsupportedPenalties(t);
    }

    /**
     * unsupported_value(temperature) 형태의 오류 감지.
     * ERROR_AW 패턴: "Unsupported value: 'temperature' does not support 0.3 ... Only the default (1)"
     */
    public static boolean isUnsupportedTemperature(Throwable t) {
        return isUnsupportedParam(t, "temperature");
    }

    public static boolean isUnsupportedTopP(Throwable t) {
        return isUnsupportedParam(t, "top_p") || isUnsupportedParam(t, "topP");
    }

    public static boolean isUnsupportedPenalties(Throwable t) {
        return isUnsupportedParam(t, "frequency_penalty")
                || isUnsupportedParam(t, "presence_penalty")
                || isUnsupportedParam(t, "frequencyPenalty")
                || isUnsupportedParam(t, "presencePenalty");
    }

    private static boolean isUnsupportedParam(Throwable t, String paramKey) {
        Throwable cur = t;
        int guard = 0;
        String p = (paramKey == null) ? "" : paramKey.toLowerCase(Locale.ROOT);
        while (cur != null && guard++ < 8) {
            String msg = cur.getMessage();
            if (msg != null) {
                String s = msg.toLowerCase(Locale.ROOT);
                // ERROR_AW 패턴 매칭
                if (s.contains("unsupported value") && s.contains(p)) return true;
                if (s.contains("unsupported_value") && s.contains(p)) return true;
                if (s.contains("\"param\"") && s.contains(p)) return true;
                if (s.contains("invalid_request_error") && s.contains(p)) return true;
                if (s.contains("only the default") && s.contains(p)) return true;
                if (s.contains("unsupported parameter") && s.contains(p)) return true;
                if (s.contains("unknown parameter") && s.contains(p)) return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    static String normalizeBaseUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/v1")) {
            s = s.substring(0, s.length() - 3);
        }
        return s;
    }
}
