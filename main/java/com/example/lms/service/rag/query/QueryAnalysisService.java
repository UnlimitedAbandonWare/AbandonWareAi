package com.example.lms.service.rag.query;

import com.example.lms.service.rag.query.QueryAnalysisResult.QueryIntent;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 기반 형태소 분석 및 쿼리 파라메터 추출 서비스
 * 
 * <p>
 * 사용자 쿼리를 LLM에게 전송하여 구조화된 검색 파라메터를 추출합니다.
 * 의도(Intent), 엔티티(Entities), 시간성(Temporal), 확장 키워드 등을 분석합니다.
 * 
 * <p>
 * 모든 품목에 대해 동작하며, 하드코딩된 키워드 필터 없이 범용적으로 사용됩니다.
 */
@Service
@RequiredArgsConstructor
public class QueryAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalysisService.class);

    /**
     * Prefer a fast/utility model for query analysis.
     * Fail-soft: if no fast model exists, this field stays null and we fall back to heuristics.
     */
    @Autowired(required = false)
    @Qualifier("fastChatModel")
    private ChatModel chatModel;

    @Autowired
    @Qualifier("llmFastExecutor")
    private ExecutorService llmFastExecutor;

    @Value("${query-analysis.enabled:true}")
    private boolean enabled;

    @Value("${query-analysis.timeout-ms:3000}")
    private long timeoutMs;

    @Value("${query-analysis.fallback-to-exploration:true}")
    private boolean fallbackToExploration;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 빠른 폴백을 위한 기본 패턴 (LLM 호출 전 프리필터)
    private static final Pattern EXPLORATION_PATTERN = Pattern.compile(
            "(?i)(찾아|검색|조회|알려|어디|뭐야|알아봐|살펴|정보)");

    private static final Pattern FRESH_PATTERN = Pattern.compile(
            "(?i)(출시|발표|루머|소문|스펙|신제품|최신|동향|근황|뉴스|업데이트|패치|공지|일정)");

    
    private static final String ANALYSIS_PROMPT = """
            당신은 한국어 쿼리 분석 전문가입니다.
            사용자 쿼리를 분석하여 아래 JSON 형식으로만 응답하세요. JSON 외의 다른 텍스트는 출력하지 마세요.

            {
              "intent": "SEARCH|INFO|COMPARE|TRENDING|GENERAL",
              "entities": ["핵심 엔티티(인물/제품/장소)"],
              "expandedKeywords": ["관련 속성 키워드"],
              "wantsFresh": true또는false,
              "isExploration": true또는false,
              "searchQueries": ["검색 쿼리1", "검색 쿼리2"],
              "confidenceScore": 0.0~1.0,
              "decisionValueScore": 0.0~1.0,
              "optimismScore": 0.0~1.0,
              "resourceTier": "LOW|MEDIUM|HIGH|CRITICAL",
              "expectedDomain": "쿼리가 속한 도메인 (예: 의학, 스마트폰, 영화)",
              "contextHints": ["엔티티를 특정하는 힌트 키워드 (소속, 제조사 등)"],
              "noiseDomains": ["제외해야 할 동음이의어 도메인"]
            }

            분석 규칙:
            - "찾아봐", "검색해줘", "조회해줘", "알아봐" → isExploration=true, intent=SEARCH
            - "~에 대해", "~관해", "~알려줘", "~뭐야" → intent=INFO
            - "비교", "뭐가 나아", "차이점" → intent=COMPARE
            - "출시", "발표", "루머", "소문", "스펙", "동향", "근황" → wantsFresh=true, intent=TRENDING
            - entities: 제품명, 브랜드, 인물, 장소 등 모든 고유명사 (제한 없음)
            - expandedKeywords: 관련 속성 (가격, 스펙, 출시일, 특징 등)
            - searchQueries: 엔티티 + 키워드 조합으로 3~5개 검색 쿼리 생성
            - confidenceScore: 분석 확신도 (0.0~1.0)
            - decisionValueScore: 의사결정 비용/중요도 (작은 물건 판매=LOW, DGX/H100 구매·계약·법률·의료·금융=HIGH 이상)
            - optimismScore: 낙관적 추정 또는 근거 부족 상태에서 과신 가능성 (0.0~1.0)
            - resourceTier: LOW|MEDIUM|HIGH|CRITICAL 중 하나
            - expectedDomain: 쿼리의 주요 도메인 또는 분야 (예: "의학", "스마트폰")
            - contextHints: 엔티티를 특정하는 소속/브랜드/지역 정보 (예: "을지대", "대전을지대학교병원")
            - noiseDomains: 동명이인/동명 제품에서 제외해야 할 도메인 (예: "경제학", "정치인")

            사용자 쿼리: %s
            """;

    /**
     * 쿼리 분석 수행
     * 
     * <p>
     * LLM을 사용하여 쿼리를 분석하고, 실패 시 폴백 로직을 적용합니다.
     * 
     * @param userQuery 사용자 원본 쿼리
     * @return 분석 결과 (실패 시 폴백 결과 반환)
     */
    public QueryAnalysisResult analyze(String userQuery) {
        if (!enabled || userQuery == null || userQuery.isBlank()) {
            log.debug("[QueryAnalysis] Analysis disabled or empty query");
            return QueryAnalysisResult.empty(userQuery);
        }

        // 빠른 휴리스틱 체크 (LLM 호출 전)
        boolean quickExploration = EXPLORATION_PATTERN.matcher(userQuery).find();
        boolean quickFresh = FRESH_PATTERN.matcher(userQuery).find();

        // LLM이 없으면 휴리스틱 기반 폴백
        if (chatModel == null) {
            log.warn("[QueryAnalysis] ChatModel not available, using heuristic fallback");
            return createHeuristicResult(userQuery, quickExploration, quickFresh);
        }

        FutureTask<QueryAnalysisResult> future = null;
        try {
            // LLM 분석 수행 (Hard timeout 적용)
            future = new FutureTask<>(() -> {
                try {
                    String queryAnalysisPrompt = String.format(ANALYSIS_PROMPT, userQuery);
                    String response = "";
                    try {
                        var resp = chatModel.chat(java.util.List.of(dev.langchain4j.data.message.UserMessage.from(queryAnalysisPrompt)));
                        if (resp != null && resp.aiMessage() != null && resp.aiMessage().text() != null) {
                            response = resp.aiMessage().text();
                        }
                    } catch (Exception llmEx) {
                        TraceStore.inc("queryAnalysis.llm.failed");
                        TraceStore.putIfAbsent("queryAnalysis.bypassed", "true");
                        TraceStore.putIfAbsent("queryAnalysis.reason", safeErrorType(llmEx));
                        log.warn("[AWX][query-analysis] llm call failed failureReason={} errorType={} queryHash12={} queryLength={}",
                                "llm-call-error", safeErrorType(llmEx), SafeRedactor.hash12(userQuery),
                                userQuery == null ? 0 : userQuery.length());
                    }
                    return parseJsonResponse(userQuery, response, quickExploration, quickFresh);
                } catch (Exception e) {
                    log.warn("[AWX][query-analysis] llm analysis failed failureReason={} errorType={} queryHash12={} queryLength={}",
                            "llm-analysis-error", safeErrorType(e), SafeRedactor.hash12(userQuery), userQuery == null ? 0 : userQuery.length());
                    return null;
                }
            });
            llmFastExecutor.execute(future);

            QueryAnalysisResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result != null) {
                log.info("[QueryAnalysis] Analysis complete: intent={}, entityCount={}, entityHash={}, exploration={}, fresh={}, tier={}, valueScore={}, optimism={}, riskAdjustedConfidence={}",
                        result.intent(), result.entities().size(), SafeRedactor.hash12(String.join("|", result.entities())), result.isExploration(), result.wantsFresh(),
                        result.resourceTier(), result.decisionValueScore(), result.optimismScore(),
                        result.riskAdjustedConfidence());
                return result;
            }

        } catch (TimeoutException e) {
            if (future != null) future.cancel(true);
            log.warn("[QueryAnalysis] Analysis timed out ({}ms), owned task cancellation requested", timeoutMs);
        } catch (InterruptedException ie) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[QueryAnalysis] Interrupted while analyzing query; owned task cancellation requested");
            return QueryAnalysisResult.empty(userQuery);
        } catch (Exception e) {
            log.warn("[AWX][query-analysis] analysis failed failureReason={} errorType={} queryHash12={} queryLength={}",
                    "analysis-error", safeErrorType(e), SafeRedactor.hash12(userQuery), userQuery == null ? 0 : userQuery.length());
        }

        // 폴백 처리
        if (fallbackToExploration) {
            log.info("[QueryAnalysis] Using exploration fallback queryHash={}", SafeRedactor.hash12(userQuery));
            return QueryAnalysisResult.explorationFallback(userQuery);
        }

        return createHeuristicResult(userQuery, quickExploration, quickFresh);
    }

    /**
     * 휴리스틱 기반 분석 결과 생성 (LLM 없이)
     */
    private QueryAnalysisResult createHeuristicResult(String query, boolean isExploration, boolean wantsFresh) {
        QueryIntent intent = QueryIntent.GENERAL;
        if (isExploration)
            intent = QueryIntent.SEARCH;
        else if (wantsFresh)
            intent = QueryIntent.TRENDING;
        ResourceProfile resource = deriveResourceProfile(query, intent, wantsFresh, isExploration);

        // 간단한 엔티티 추출 (공백으로 분리된 토큰 중 2글자 이상)
        List<String> entities = new ArrayList<>();
        for (String token : query.split("\\s+")) {
            String cleaned = token.replaceAll("[^가-힣a-zA-Z0-9]", "");
            if (cleaned.length() >= 2) {
                entities.add(cleaned);
            }
        }

        return new QueryAnalysisResult(
                query,
                intent,
                entities,
                Collections.emptyList(),
                wantsFresh,
                isExploration,
                List.of(query),
                0.5,
                resource.valueScore(),
                resource.optimismScore(),
                resource.tier(),
                null,
                Collections.emptyList(),
                Collections.emptyList());
    }

    /**
     * LLM JSON 응답 파싱
     */
    private QueryAnalysisResult parseJsonResponse(String original, String response,
            boolean heuristicExploration, boolean heuristicFresh) {
        try {
            // JSON 부분만 추출 (LLM이 추가 텍스트를 출력할 수 있음)
            String json = extractJson(response);
            if (json == null) {
                log.warn("[QueryAnalysis] No valid JSON found in response");
                return createHeuristicResult(original, heuristicExploration, heuristicFresh);
            }

            JsonNode root = objectMapper.readTree(json);

            String intentStr = getTextOrDefault(root, "intent", "GENERAL");
            QueryIntent intent = parseIntent(intentStr);

            List<String> entities = getStringList(root, "entities");
            List<String> expandedKeywords = getStringList(root, "expandedKeywords");
            List<String> searchQueries = getStringList(root, "searchQueries");

            // [NEW] 엔티티 도메인/컨텍스트/노이즈 필드 파싱
            String expectedDomain = getTextOrDefault(root, "expectedDomain", null);
            List<String> contextHints = getStringList(root, "contextHints");
            List<String> noiseDomains = getStringList(root, "noiseDomains");

            boolean wantsFresh = getBooleanOrDefault(root, "wantsFresh", heuristicFresh);
            boolean isExploration = getBooleanOrDefault(root, "isExploration", heuristicExploration);
            double confidence = getDoubleOrDefault(root, "confidenceScore", 0.7);
            ResourceProfile resource = deriveResourceProfile(original, intent, wantsFresh, isExploration);
            double decisionValue = getDoubleOrDefault(root, "decisionValueScore", resource.valueScore());
            double optimism = getDoubleOrDefault(root, "optimismScore", resource.optimismScore());
            String resourceTier = getTextOrDefault(root, "resourceTier", resource.tier());

            return new QueryAnalysisResult(
                    original,
                    intent,
                    entities,
                    expandedKeywords,
                    wantsFresh,
                    isExploration,
                    searchQueries,
                    confidence,
                    decisionValue,
                    optimism,
                    resourceTier,
                    expectedDomain,
                    contextHints,
                    noiseDomains);        } catch (Exception e) {
            log.warn("[AWX][query-analysis] json parsing failed failureReason={} errorType={} responseHash12={} responseLength={}",
                    "json-parse-error", safeErrorType(e), SafeRedactor.hash12(response), response == null ? 0 : response.length());
            return createHeuristicResult(original, heuristicExploration, heuristicFresh);
        }
    }

    private static String safeErrorType(Throwable error) {
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }

    private String extractJson(String text) {
        if (text == null)
            return null;

        // JSON 블록 추출 시도
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private QueryIntent parseIntent(String intentStr) {
        try {
            return QueryIntent.valueOf(intentStr.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            TraceStore.put("query.analysis.suppressed.stage", "intentParse");
            TraceStore.put("query.analysis.suppressed.errorType", "invalid_intent");
            TraceStore.put("query.analysis.suppressed.intentParse", true);
            TraceStore.put("query.analysis.suppressed.intentParse.errorType", "invalid_intent");
            return QueryIntent.GENERAL;
        }
    }

    private String getTextOrDefault(JsonNode node, String field, String defaultVal) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : defaultVal;
    }

    private boolean getBooleanOrDefault(JsonNode node, String field, boolean defaultVal) {
        JsonNode child = node.get(field);
        return (child != null && child.isBoolean()) ? child.asBoolean() : defaultVal;
    }

    private double getDoubleOrDefault(JsonNode node, String field, double defaultVal) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.asDouble() : defaultVal;
    }

    private ResourceProfile deriveResourceProfile(String query, QueryIntent intent, boolean wantsFresh,
            boolean isExploration) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        double valueScore = switch (intent) {
            case COMPARE -> 0.52d;
            case TRENDING -> 0.46d;
            case SEARCH, INFO -> 0.36d;
            default -> 0.26d;
        };
        double optimismScore = isExploration ? 0.48d : 0.30d;

        if (wantsFresh) {
            valueScore += 0.08d;
            optimismScore += 0.05d;
        }
        if (containsAny(normalized, "dgx", "h100", "spark", "gpu", "\uacac\uc801", "\uacc4\uc57d",
                "\uad6c\ub9e4", "\ud22c\uc790", "\ubc95\ub960", "\uc758\ub8cc", "\uae08\uc735")) {
            valueScore = Math.max(valueScore, 0.78d);
            optimismScore += 0.18d;
        }
        if (containsAny(normalized, "\uc18c\uc1a1", "\uc138\uae08", "\ubcf4\ud5d8", "invoice", "quote",
                "contract", "lawsuit", "medical", "finance", "investment")) {
            valueScore = Math.max(valueScore, 0.72d);
            optimismScore += 0.14d;
        }
        if (containsAny(normalized, "\uc911\uace0", "\uc791\uc740", "\ud310\ub9e4 \ubb38\uad6c",
                "small", "used item", "listing copy")) {
            valueScore = Math.min(valueScore, 0.22d);
            optimismScore = Math.min(optimismScore, 0.30d);
        }

        valueScore = clamp(valueScore, 0.0d, 1.0d);
        optimismScore = clamp(optimismScore, 0.0d, 1.0d);
        String tier = valueScore >= 0.90d ? "CRITICAL"
                : valueScore >= 0.65d ? "HIGH"
                : valueScore <= 0.25d ? "LOW" : "MEDIUM";
        return new ResourceProfile(valueScore, optimismScore, tier);
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank()
                    && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private record ResourceProfile(double valueScore, double optimismScore, String tier) {
    }

    private List<String> getStringList(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isArray()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : child) {
            if (item.isTextual()) {
                result.add(item.asText());
            }
        }
        return result;
    }
}
