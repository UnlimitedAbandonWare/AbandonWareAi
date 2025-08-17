package com.example.lms.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * ChatService v7 호환 ChatRequestDto
 */
@Data
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
         * Optional base64 encoded image payload.  If provided, downstream
         * services may invoke multimodal models to extract information
         * from the image.  The payload should not include any data URI
         * prefix (e.g., "data:image/png;base64,") and consist solely of
         * the raw base64 data.  When null, no image was attached.
         */
        private String imageBase64;

        /* ───── Jackson Setter : RAG ON ⇒ WebSearch ON ───── */
        public void setUseRag(boolean useRag) {
                this.useRag = useRag;
                if (useRag && !webSearchExplicit) this.useWebSearch = true;
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
