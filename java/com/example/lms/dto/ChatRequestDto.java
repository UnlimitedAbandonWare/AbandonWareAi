package com.example.lms.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ChatService v7 호환 ChatRequestDto
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRequestDto {
        @Builder.Default      // 빌더 사용 시 기본값
        private boolean  useVerification = false;
        /* ────────── ① 필수 프롬프트 ────────── */
        private String message;
        private String systemPrompt;
        private List<Message> history;

        /* ────────── ② 모델 & 샘플링 ────────── */
        @Builder.Default private String  model            = "gpt-3.5-turbo";
        @Builder.Default private Double  temperature      = 0.7;
        @Builder.Default @JsonProperty("top_p")
        private Double  topP           = 1.0;
        @Builder.Default private Double  frequencyPenalty = 0.0;
        @Builder.Default private Double  presencePenalty  = 0.0;
        @Builder.Default private Integer maxTokens        = 2048;

        /* ────────── ③ 서비스 스위치 ────────── */
        private Long   sessionId;                 // nullable
        @JsonProperty("useRag")      @JsonAlias("use_rag")
        @Builder.Default private boolean useRag        = false;
        @JsonProperty("useWebSearch") @JsonAlias("use_web_search")
        @Builder.Default private boolean useWebSearch  = false;

        /** true → RAG 단독 실행 / false → 컨텍스트 주입 */
        private Boolean ragStandalone;

        @Builder.Default private boolean useAdaptive   = false;
        @Builder.Default private boolean autoTranslate = false;
        @Builder.Default private boolean polish        = false;

        /**
         * When true the chat pipeline will generate a structured summary of the
         * assistant's final answer (TL;DR, key points and action items) using
         * Gemini.  The summary will be saved to long‑term memory and emitted
         * to the front‑end via SSE.  Defaults to false.
         */
        @Builder.Default
        private boolean understandingEnabled = false;

        /*
         * Indicates the origin of the user's input.  When the
         * client submits a request generated via the Web Speech API,
         * this field should be set to "voice".  For all other inputs
         * (typed text or programmatic requests) it defaults to
         * "text".  Downstream services can use this hint to adjust
         * query correction or disambiguation strategies.
         */
        @Builder.Default
        private String inputType = "text";

        /* ────────── ④ 토큰 한도 ────────── */
        @Builder.Default private Integer maxMemoryTokens = 7_500;
        @Builder.Default private Integer maxRagTokens    = 5_000;

        /*
         * ────────── ⑤ 검색 모드 ──────────
         *
         * Controls how the backend performs web search.  When set to AUTO (default)
         * the system will decide dynamically whether to execute a web query based on
         * the question complexity and the assistant’s own analysis.  OFF disables
         * live web search entirely.  FORCE_LIGHT performs a single lightweight
         * search with a short snippet limit, while FORCE_DEEP runs a full multi‑step
         * search with self‑ask decomposition and verification.  This field is
         * independent of the existing retrieval.mode setting and gives the user
         * explicit control over the search plugin.  See {@link
         * com.example.lms.gptsearch.dto.SearchMode} for mode definitions.
         */
        @JsonProperty("searchMode")
        @Builder.Default
        private com.example.lms.gptsearch.dto.SearchMode searchMode = com.example.lms.gptsearch.dto.SearchMode.AUTO;

        /**
         * Optional list of preferred web search providers.  When null or empty the
         * system will choose from its configured provider list (e.g. Bing,
         * Tavily, GoogleCSE).  Clients can specify multiple provider IDs such as
         * ["BING", "TAVILY"] to query multiple backends in parallel.  This
         * attribute is ignored when searchMode is OFF.
         */
        @JsonProperty("webProviders")
        @Builder.Default
        private java.util.List<String> webProviders = null;

        /* ────────── ⑤ 검색 개수(top‑k) ────────── */
        /** 프런트 슬라이더로 넘어오는 웹 스니펫 개수(네이버 display와 동기) */
        @JsonProperty("webTopK")
        @Builder.Default
        private Integer webTopK = 5;

        /*
         * Advanced retrieval controls
         *
         * If true, the retrieval layer should filter results to only those
         * originating from trusted, official domains.  The list of official
         * domains is configured via the property `search.official.domains`.
         * When false or null, all domains are considered and ranked.
         */
        @Builder.Default
        private Boolean officialSourcesOnly = false;

        /**
         * User-selected search scopes.  Allowed values include "web" to
         * enable live/hybrid web search and "documents" to enable vector
         * database retrieval.  When the list is null or empty, both
         * retrieval modes are assumed enabled.  The frontend encodes this
         * field as a JSON array of strings.
         */
        @Builder.Default
        private java.util.List<String> searchScopes = java.util.Collections.emptyList();

        /**
         * 정밀 검색(대용량 웹 스캔) 실행 여부.  When true the web search layer will
         * retrieve the full bodies of the top N results and combine them into
         * a single context for summarization.  Defaults to false.
         */
        @Builder.Default
        private Boolean precisionSearch = false;

        /**
         * 정밀 검색 시 스캔할 URL 개수.  If null, the system will fall back to
         * the standard {@code webTopK} value or a default of 10.  Only used
         * when {@code precisionSearch} is true.
         */
        private Integer precisionTopK;

        /**
         * Optional base64 encoded image payload.  If provided, downstream
         * services may invoke multimodal models to extract information
         * from the image.  The payload should not include any data URI
         * prefix (e.g., "data:image/png;base64,") and consist solely of
         * the raw base64 data.  When null, no image was attached.
         */
        private String imageBase64;

        /** Optional list of attachment IDs associated with this message */
        private java.util.List<String> attachmentIds;

        /* ───── Jackson Setter : 부작용 제거 ───── */
        /**
         * Set whether to enable RAG (vector retrieval).
         *
         * The previous implementation implicitly enabled the web search flag
         * whenever RAG was turned on.  This coupling caused the web search
         * toggle to flip on unexpectedly whenever the client enabled RAG.
         * To honour the user's explicit intent, the setter now only updates
         * the {@code useRag} field and does not modify {@code useWebSearch}.
         *
         * @param useRag true to enable retrieval via the vector database, false otherwise
         */
        public void setUseRag(boolean useRag) {
                // assign the field directly; do not toggle useWebSearch implicitly
                this.useRag = useRag;
        }
        public void setUseWebSearch(boolean useWebSearch) {
                this.useWebSearch   = useWebSearch;
                this.webSearchExplicit = true;
        }

        /** 내부 플래그 - 직렬화 제외 */
        @Getter(AccessLevel.NONE)
        @Setter(AccessLevel.NONE)
        @Builder.Default
        private transient boolean webSearchExplicit = false;

        /* ────────── ⑤ 대화 이력 DTO ────────── */
        @Data @NoArgsConstructor @AllArgsConstructor @Builder
        public static class Message {
                private String role;
                private String content;
        }
}
