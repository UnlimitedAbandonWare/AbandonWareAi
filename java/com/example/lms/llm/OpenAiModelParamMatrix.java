package com.example.lms.llm;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 모델/엔드포인트별 요청 파라미터 매트릭스.
 *
 * <p>목적:
 * <ul>
 *   <li>OpenAI API(및 일부 프록시/게이트웨이)에서 모델별로 토큰 제한 파라미터가 다를 수 있음</li>
 *   <li>예: gpt-5.*, o1/o3/o4 계열은 chat/completions에서 legacy {@code max_tokens}를 거부하고
 *       {@code max_completion_tokens}를 요구할 수 있음</li>
 *   <li>반대로 OpenAI-compatible 로컬 게이트웨이(vLLM/Ollama 등)는 {@code max_tokens} 중심</li>
 * </ul>
 *
 * <p>이 매트릭스는 baseUrl(공식/프록시/게이트웨이) + modelName(정확/접두사)에 따라
 * 어떤 토큰 파라미터 키를 사용할지 런타임(Spring config)에서 결정한다.
 *
 * <p>설정 예시는 {@code src/main/resources/openai-model-param-matrix.yaml} 참고.
 */
@Component
@ConfigurationProperties(prefix = "llm.openai.param-matrix")
public class OpenAiModelParamMatrix {

    public enum TokenParam {
        MAX_TOKENS("max_tokens"),
        MAX_COMPLETION_TOKENS("max_completion_tokens"),
        NONE("none");

        private final String key;

        TokenParam(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static TokenParam from(String raw) {
            if (raw == null) return MAX_TOKENS;
            String v = raw.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) return MAX_TOKENS;
            if (v.equals("max_completion_tokens") || v.equals("completion") || v.equals("completion_tokens")) {
                return MAX_COMPLETION_TOKENS;
            }
            if (v.equals("none") || v.equals("omit") || v.equals("disabled")) {
                return NONE;
            }
            return MAX_TOKENS;
        }
    }

    /** root default (기본: max_tokens) */
    private String tokenParamDefault = "max_tokens";

    /** exact match: modelId -> tokenParam */
    private Map<String, String> byModel = new LinkedHashMap<>();

    /** prefix match: prefix -> tokenParam */
    private Map<String, String> byPrefix = new LinkedHashMap<>();

    /**
     * baseUrl match (substring, case-insensitive) -> rules
     *
     * <p>예: "api.openai.com": { by-prefix: { gpt-5: max_completion_tokens } }
     */
    private Map<String, RuleSet> byBaseUrl = new LinkedHashMap<>();

    public static class RuleSet {
        private String tokenParamDefault;
        private Map<String, String> byModel = new LinkedHashMap<>();
        private Map<String, String> byPrefix = new LinkedHashMap<>();

        public String getTokenParamDefault() {
            return tokenParamDefault;
        }

        public void setTokenParamDefault(String tokenParamDefault) {
            this.tokenParamDefault = tokenParamDefault;
        }

        public Map<String, String> getByModel() {
            return byModel;
        }

        public void setByModel(Map<String, String> byModel) {
            this.byModel = (byModel == null) ? new LinkedHashMap<>() : byModel;
        }

        public Map<String, String> getByPrefix() {
            return byPrefix;
        }

        public void setByPrefix(Map<String, String> byPrefix) {
            this.byPrefix = (byPrefix == null) ? new LinkedHashMap<>() : byPrefix;
        }
    }

    @PostConstruct
    void registerCompatBridge() {
        // Static helper가 config를 알 수 있도록 연결 (fail-soft)
        OpenAiTokenParamCompat.registerMatrix(this);
    }

    public String getTokenParamDefault() {
        return tokenParamDefault;
    }

    public void setTokenParamDefault(String tokenParamDefault) {
        this.tokenParamDefault = tokenParamDefault;
    }

    public Map<String, String> getByModel() {
        return byModel;
    }

    public void setByModel(Map<String, String> byModel) {
        this.byModel = (byModel == null) ? new LinkedHashMap<>() : byModel;
    }

    public Map<String, String> getByPrefix() {
        return byPrefix;
    }

    public void setByPrefix(Map<String, String> byPrefix) {
        this.byPrefix = (byPrefix == null) ? new LinkedHashMap<>() : byPrefix;
    }

    public Map<String, RuleSet> getByBaseUrl() {
        return byBaseUrl;
    }

    public void setByBaseUrl(Map<String, RuleSet> byBaseUrl) {
        this.byBaseUrl = (byBaseUrl == null) ? new LinkedHashMap<>() : byBaseUrl;
    }

    /**
     * Resolve which token parameter should be used for the given baseUrl + modelName.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>baseUrl-matched ruleset (longest substring match)</li>
     *   <li>ruleset.byModel exact match</li>
     *   <li>ruleset.byPrefix longest prefix match</li>
     *   <li>ruleset.tokenParamDefault (or global default if missing)</li>
     *   <li>Safety fallback: official OpenAI + (gpt-5/o-series) => MAX_COMPLETION_TOKENS</li>
     * </ol>
     */
    public TokenParam resolveTokenParam(String modelName, String baseUrl) {
        RuleSet rs = findRuleSetForBaseUrl(baseUrl);

        // 1) baseUrl ruleset: exact/prefix override only
        TokenParam baseOverride = null;
        if (rs != null) {
            TokenParam exactOrPrefix = resolveExactOrPrefix(modelName, rs.getByModel(), rs.getByPrefix());
            if (exactOrPrefix != null) {
                baseOverride = exactOrPrefix;
            } else if (rs.getTokenParamDefault() != null && !rs.getTokenParamDefault().isBlank()) {
                baseOverride = TokenParam.from(rs.getTokenParamDefault());
            }
        }

        // 2) global: exact/prefix
        TokenParam resolved = resolveExactOrPrefix(modelName, byModel, byPrefix);
        if (resolved == null) {
            resolved = (baseOverride != null) ? baseOverride : TokenParam.from(tokenParamDefault);
        }

        // Safety fallback: 공식 OpenAI + gpt-5/o-series면 max_completion_tokens 쪽으로 강제
        if (resolved == TokenParam.MAX_TOKENS
                && OpenAiTokenParamCompat.usesMaxCompletionTokens(modelName)
                && OpenAiTokenParamCompat.isOfficialOpenAi(baseUrl)) {
            return TokenParam.MAX_COMPLETION_TOKENS;
        }

        return resolved;
    }

    /** Returns the JSON key name to use; returns null when NONE. */
    public String tokenParamKey(String modelName, String baseUrl) {
        TokenParam p = resolveTokenParam(modelName, baseUrl);
        if (p == null || p == TokenParam.NONE) {
            return null;
        }
        return p.key();
    }

    private static TokenParam resolveExactOrPrefix(String modelName,
                                                 Map<String, String> byModel,
                                                 Map<String, String> byPrefix) {
        String m = (modelName == null) ? "" : modelName.trim();
        if (m.isEmpty()) {
            return null;
        }

        // exact match
        if (byModel != null && !byModel.isEmpty()) {
            for (Map.Entry<String, String> e : byModel.entrySet()) {
                if (e.getKey() == null) continue;
                if (m.equalsIgnoreCase(e.getKey().trim())) {
                    return TokenParam.from(e.getValue());
                }
            }
        }

        // longest prefix match
        if (byPrefix != null && !byPrefix.isEmpty()) {
            String lower = m.toLowerCase(Locale.ROOT);
            String bestKey = null;
            String bestVal = null;
            int bestLen = -1;
            for (Map.Entry<String, String> e : byPrefix.entrySet()) {
                String p = (e.getKey() == null) ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
                if (p.isEmpty()) continue;
                if (lower.startsWith(p) && p.length() > bestLen) {
                    bestLen = p.length();
                    bestKey = p;
                    bestVal = e.getValue();
                }
            }
            if (bestKey != null) {
                return TokenParam.from(bestVal);
            }
        }

        return null;
    }

    private RuleSet findRuleSetForBaseUrl(String baseUrl) {
        if (byBaseUrl == null || byBaseUrl.isEmpty()) {
            return null;
        }
        String norm = normalizeBaseUrl(baseUrl);
        if (norm.isEmpty()) {
            return null;
        }

        RuleSet best = null;
        int bestLen = -1;
        for (Map.Entry<String, RuleSet> e : byBaseUrl.entrySet()) {
            String rawKey = e.getKey();
            if (rawKey == null || rawKey.isBlank()) {
                continue;
            }
            String marker = normalizeBaseUrl(rawKey);
            if (marker.isEmpty()) {
                marker = rawKey.trim().toLowerCase(Locale.ROOT);
            }
            if (norm.contains(marker) && marker.length() > bestLen) {
                bestLen = marker.length();
                best = e.getValue();
            }
        }
        return best;
    }

    private static String normalizeBaseUrl(String raw) {
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
