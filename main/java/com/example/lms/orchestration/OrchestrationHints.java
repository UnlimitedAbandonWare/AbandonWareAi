package com.example.lms.orchestration;

import lombok.Builder;
import lombok.Data;

/**
 * plate를 전역 setter가 아닌 요청 단위로 전달하기 위한 힌트 객체.
 * HybridRetriever/WebSearchRetriever가 이 값을 읽어 실제 topK/timeout 적용.
 */
@Data
@Builder(toBuilder = true)
public class OrchestrationHints {
    private String plateId;
    private Integer webTopK;
    private Integer vecTopK;
    private Long webBudgetMs;
    private Long vecBudgetMs;
    private Long auxLlmBudgetMs;
    private boolean enableSelfAsk;
    private boolean enableAnalyze;
    private boolean enableCrossEncoder;
    private boolean nightmareMode;
    // 보조 LLM(쿼리 변형/디스앰비규에이션/키워드 선택) 장애 플래그
    private boolean auxLlmDown;
    private boolean allowWeb;
    private boolean allowRag;

    // ── Orchestration mode wiring (UAW: STRIKE/COMPRESSION/BYPASS) ──────────
    private boolean strikeMode;
    private boolean compressionMode;
    private boolean bypassMode;
    private boolean webRateLimited;
    private String bypassReason;

    public static OrchestrationHints defaults() {
        return OrchestrationHints.builder()
                .webTopK(5)
                .vecTopK(10)
                .webBudgetMs(3000L)
                .vecBudgetMs(3000L)
                .auxLlmBudgetMs(1200L)
                .enableSelfAsk(false)
                .enableAnalyze(true)
                .enableCrossEncoder(true)
                .nightmareMode(false)
                .auxLlmDown(false)
                .allowWeb(true)
                .allowRag(true)
                .strikeMode(false)
                .compressionMode(false)
                .bypassMode(false)
                .webRateLimited(false)
                .bypassReason(null)
                .build();
    }
}
