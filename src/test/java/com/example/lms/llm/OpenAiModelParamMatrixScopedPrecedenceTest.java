package com.example.lms.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.lms.llm.OpenAiModelParamMatrix.TokenParam.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiModelParamMatrixScopedPrecedenceTest {
    private static final String MODEL = "model-v2";
    private static final String BASE = "https://gateway.example/v1";

    @ParameterizedTest
    @CsvSource({
            "model,model,max_tokens,omit,MAX_TOKENS",
            "model,prefix,none,max_tokens,NONE",
            "prefix,model,max_completion_tokens,max_tokens,MAX_COMPLETION_TOKENS",
            "prefix,prefix,max_tokens,max_completion_tokens,MAX_TOKENS",
            "default,model,disabled,max_completion_tokens,NONE",
            "default,prefix,max_completion_tokens,none,MAX_COMPLETION_TOKENS"
    })
    void endpointRuleWinsConflictingGlobalRule(String scopedKind, String globalKind,
                                              String scopedValue, String globalValue,
                                              OpenAiModelParamMatrix.TokenParam expected) {
        OpenAiModelParamMatrix matrix = new OpenAiModelParamMatrix();
        addGlobalRule(matrix, globalKind, globalValue);
        OpenAiModelParamMatrix.RuleSet scoped = new OpenAiModelParamMatrix.RuleSet();
        switch (scopedKind) {
            case "model" -> scoped.setByModel(Map.of(MODEL, scopedValue));
            case "prefix" -> scoped.setByPrefix(Map.of("model-", scopedValue));
            case "default" -> scoped.setTokenParamDefault(scopedValue);
            default -> fail("unknown fixture scope");
        }
        matrix.setByBaseUrl(Map.of("gateway.example", scoped));
        assertResolution(matrix, MODEL, BASE, expected);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "model|NULL", "model|EMPTY", "model|SPACE",
            "prefix|NULL", "prefix|EMPTY", "prefix|SPACE"
    }, delimiter = '|')
    void scopeWithoutApplicableRuleFallsBackToGlobal(String globalKind, String blankKind) {
        OpenAiModelParamMatrix matrix = new OpenAiModelParamMatrix();
        addGlobalRule(matrix, globalKind, "max_completion_tokens");
        OpenAiModelParamMatrix.RuleSet scoped = new OpenAiModelParamMatrix.RuleSet();
        scoped.setByModel(Map.of("another-model", "none"));
        scoped.setByPrefix(Map.of("unmatched-", "none"));
        scoped.setTokenParamDefault(switch (blankKind) {
            case "NULL" -> null;
            case "EMPTY" -> "";
            default -> "   ";
        });
        matrix.setByBaseUrl(Map.of("gateway.example", scoped));
        assertResolution(matrix, MODEL, BASE, MAX_COMPLETION_TOKENS);
    }

    @Test
    void unmatchedEndpointKeepsGlobalExactBeforePrefix() {
        OpenAiModelParamMatrix matrix = new OpenAiModelParamMatrix();
        matrix.setByModel(Map.of(MODEL, "none"));
        matrix.setByPrefix(Map.of("model-", "max_completion_tokens"));
        OpenAiModelParamMatrix.RuleSet scoped = new OpenAiModelParamMatrix.RuleSet();
        scoped.setTokenParamDefault("max_tokens");
        matrix.setByBaseUrl(Map.of("unmatched.example", scoped));
        assertResolution(matrix, MODEL, BASE, NONE);
    }

    @Test
    void absentModelAndEndpointUseGlobalDefault() {
        OpenAiModelParamMatrix matrix = new OpenAiModelParamMatrix();
        matrix.setTokenParamDefault("max_completion_tokens");
        assertResolution(matrix, null, null, MAX_COMPLETION_TOKENS);
    }

    @Test
    void longestNormalizedEndpointRuleStillWinsWithinEndpointScope() {
        OpenAiModelParamMatrix matrix = new OpenAiModelParamMatrix();
        OpenAiModelParamMatrix.RuleSet broad = new OpenAiModelParamMatrix.RuleSet();
        broad.setTokenParamDefault("max_tokens");
        OpenAiModelParamMatrix.RuleSet narrow = new OpenAiModelParamMatrix.RuleSet();
        narrow.setTokenParamDefault("none");
        Map<String, OpenAiModelParamMatrix.RuleSet> endpoints = new LinkedHashMap<>();
        endpoints.put("example", broad);
        endpoints.put("HTTPS://GATEWAY.EXAMPLE/V1/", narrow);
        matrix.setByBaseUrl(endpoints);
        assertResolution(matrix, MODEL, BASE + "/", NONE);
    }

    @ParameterizedTest
    @CsvSource({"MODEL-V2,NONE", "MODEL-V20,MAX_COMPLETION_TOKENS"})
    void endpointExactThenLongestPrefixRemainCaseInsensitive(
            String model, OpenAiModelParamMatrix.TokenParam expected) {
        OpenAiModelParamMatrix matrix = new OpenAiModelParamMatrix();
        OpenAiModelParamMatrix.RuleSet scoped = new OpenAiModelParamMatrix.RuleSet();
        scoped.setTokenParamDefault("max_tokens");
        scoped.setByModel(Map.of("model-v2", "none"));
        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put("model-", "max_tokens");
        prefixes.put("model-v", "max_completion_tokens");
        scoped.setByPrefix(prefixes);
        matrix.setByBaseUrl(Map.of("gateway.example", scoped));
        assertResolution(matrix, model, BASE, expected);
    }

    @ParameterizedTest
    @CsvSource({"max_tokens,MAX_COMPLETION_TOKENS", "omit,NONE"})
    void officialSafetyFallbackConvertsLegacyButPreservesOmission(
            String rule, OpenAiModelParamMatrix.TokenParam expected) {
        OpenAiModelParamMatrix matrix = new OpenAiModelParamMatrix();
        OpenAiModelParamMatrix.RuleSet scoped = new OpenAiModelParamMatrix.RuleSet();
        scoped.setTokenParamDefault(rule);
        matrix.setByBaseUrl(Map.of("api.openai.com", scoped));
        assertResolution(matrix, "gpt-5", "https://api.openai.com/v1", expected);
    }

    private static void addGlobalRule(OpenAiModelParamMatrix matrix, String kind, String value) {
        if (kind.equals("model")) matrix.setByModel(Map.of(MODEL, value));
        else matrix.setByPrefix(Map.of("model-", value));
    }

    private static void assertResolution(OpenAiModelParamMatrix matrix, String model,
                                         String base, OpenAiModelParamMatrix.TokenParam expected) {
        assertAll(
                () -> assertEquals(expected, matrix.resolveTokenParam(model, base)),
                () -> assertEquals(expected == NONE ? null : expected.key(), matrix.tokenParamKey(model, base)));
    }
}
