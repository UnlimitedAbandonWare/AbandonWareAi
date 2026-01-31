package com.example.lms.infra.resilience;

/**
 * NightmareBreaker의 key를 문자열 리터럴로 흩뿌리지 않기 위해 중앙집중화.
 * 오케스트레이션/가드/전처리 단계가 동일 key로 상태를 공유해야 "접목"이 된다.
 */
public final class NightmareKeys {
    private NightmareKeys() {}

    /** Query rewrite / spelling correction utility LLM */
    public static final String QUERY_TRANSFORMER_RUN_LLM = "query-transformer:runLLM";
    /** Disambiguation JSON classifier */
    public static final String DISAMBIGUATION_CLARIFY = "disambiguation:clarify";
    /** Keyword selection JSON extractor */
    public static final String KEYWORD_SELECTION_SELECT = "keyword-selection:select";

    // RAG / scoring - separate keys so failures don't block unrelated LLM utilities
    public static final String RAG_CONTRADICTION_SCORE = "rag:contradiction:score";
    public static final String OVERDRIVE_CONTRADICTION_SCORER = "overdrive:contradiction";
    /** ONNX Cross-Encoder rerank stage */
    public static final String RERANK_ONNX = "rerank:onnx";

    /** Fast utility LLM (generic) */
    public static final String FAST_LLM_COMPLETE = "llm-fast:complete";
    /** Main chat draft generation (primary response) */
    public static final String CHAT_DRAFT = "chat:draft";

    /**
     * 백엔드별로 분리된 chat:draft 키 생성.
     * OpenAI가 터져도 로컬 모델 경로의 브레이커는 열리지 않음.
     *
     * @param backendTag 모델명 또는 백엔드 식별자 (예: "gpt-5.2-chat-latest", "gemma3:27b")
     * @return "chat:draft:백엔드태그" 형식의 고유 키
     */
    public static String chatDraftKey(String backendTag) {
        String t = (backendTag == null || backendTag.isBlank()) ? "unknown" : backendTag.trim();
        // 브레이커 키 안전화: 공백/특수문자 최소화
        t = t.replaceAll("[^a-zA-Z0-9._:-]", "_");
        return CHAT_DRAFT + ":" + t;
    }
    /** Self-Ask keyword seed generation */
    public static final String SELFASK_SEED = "selfask:seed";
    /** Self-Ask follow-up keyword generation */
    public static final String SELFASK_FOLLOWUP = "selfask:followup";

    /** Web search (Naver) */
    public static final String WEBSEARCH_NAVER = "websearch:naver";
    /** Web search (Brave) */
    public static final String WEBSEARCH_BRAVE = "websearch:brave";

    /** Web search (Hybrid fan-out/join) */
    public static final String WEBSEARCH_HYBRID = "websearch:hybrid";

    /** Web fail-soft starvation (officialOnly resulted in 0 after filtering) */
    public static final String WEB_FAILSOFT_STARVED = "web-failsoft:starved";
    /** Web fail-soft misroute / stage clamp anomaly */
    public static final String WEB_FAILSOFT_MISROUTE = "web-failsoft:misroute";

    /** Vector retrieval stage (EmbeddingStore search) */
    public static final String RETRIEVAL_VECTOR = "retrieval:vector";
    /** Vector retrieval poisoning guard / rejection */
    public static final String RETRIEVAL_VECTOR_POISON = "retrieval:vector:poison";

    /** Orchestration bypass routing (fail-fast safe-path) */
    public static final String BYPASS_ROUTING = "routing:bypass";
    /** Orchestration strike mode (evidence-first) */
    public static final String STRIKE_MODE = "routing:strike";

}
