package com.example.lms.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Arrays;

/**
 * ChatService v7 호환 ChatRequestDto
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true, builderClassName = "Builder")
public class ChatRequestDto {
        @Builder.Default // 빌더 사용 시 기본값
        private boolean useVerification = false;
        /* ────────── ① 필수 프롬프트 ────────── */
        private String message;

        /**
         * Optional system prompt identifier or literal system prompt text.
         * If it looks like an id (e.g. "projection.final"), the server may resolve
         * it against classpath prompt assets (e.g. prompts/system/projection.final.md).
         */
        private String systemPrompt;

        /**
         * Optional trait identifiers that map to additional system-prompt snippets.
         * Typically resolved from classpath resources (e.g. prompts/traits/{id}.md).
         */
        private List<String> traits;
        private List<Message> history;

        /**
         * Optional answer mode: "fact" | "creative" | "balanced".
         * When null or unrecognised, the server falls back to BALANCED.
         */
        private String mode;

        /**
         * Optional memory mode: "full" | "hybrid" | "ephemeral".
         * When null or unrecognised, the server falls back to HYBRID.
         */
        @JsonProperty("memoryMode")
        @JsonAlias({ "memory_mode" })
        private String memoryMode;

        /* ────────── ② 모델 & 샘플링 ────────── */

        /**
         * Optional requested model id.
         * When null/blank, the server chooses via settings/routing policy.
         * Prefer logical ids like "llmrouter.auto" if enabled.
         */
        private String model;

        @Builder.Default
        private Double temperature = 0.7;
        @Builder.Default
        @JsonProperty("top_p")
        private Double topP = 1.0;
        @Builder.Default
        private Double frequencyPenalty = 0.0;
        @Builder.Default
        private Double presencePenalty = 0.0;
        @Builder.Default
        private Integer maxTokens = 2048;

        /* ────────── ③ 서비스 스위치 ────────── */
        private Long sessionId; // nullable
        /**
         * Flag indicating whether vector retrieval (RAG) should be used. When
         * {@code null} the client has not explicitly specified a preference and the
         * controller should fall back to its configured default. This wrapper
         * type allows us to distinguish between an explicit {@code false} and an
         * unspecified value. See ChatApiController.mergeWithSettings() for
         * normalisation logic.
         */
        @JsonProperty("useRag")
        @JsonAlias("use_rag")
        @Builder.Default
        private java.lang.Boolean useRag = null;

        /**
         * Flag indicating whether live or hybrid web search should be executed for
         * this request. As with {@link #useRag}, a {@code null} value means the
         * client has not set a preference and the server should defer to its
         * defaults. When the user explicitly turns on web search this field is
         * set to {@code true} and the {@link #webSearchExplicit} flag is also
         * flipped to {@code true} via the setter.
         */
        @JsonProperty("useWebSearch")
        @JsonAlias("use_web_search")
        @Builder.Default
        private java.lang.Boolean useWebSearch = null;

        /** true → RAG 단독 실행 / false → 컨텍스트 주입 */
        private Boolean ragStandalone;

        @Builder.Default
        private boolean useAdaptive = false;
        @Builder.Default
        private boolean autoTranslate = false;
        /**
         * When {@code true} the assistant should refine its answer by performing
         * additional grammar and style polishing. A {@code null} value indicates
         * that the client has not specified a preference. This wrapper type
         * mirrors the flags for retrieval features.
         */
        @Builder.Default
        private java.lang.Boolean polish = null;

        /**
         * When true the chat pipeline will generate a structured summary of the
         * assistant's final answer (TL;DR, key points and action items) using
         * Gemini. The summary will be saved to long-term memory and emitted
         * to the front-end via SSE. Defaults to false.
         */
        @Builder.Default
        private boolean understandingEnabled = false;

        /*
         * Indicates the origin of the user's input. When the
         * client submits a request generated via the Web Speech API,
         * this field should be set to "voice". For all other inputs
         * (typed text or programmatic requests) it defaults to
         * "text". Downstream services can use this hint to adjust
         * query correction or disambiguation strategies.
         */
        @Builder.Default
        private String inputType = "text";

        /* ────────── ④ 토큰 한도 ────────── */
        @Builder.Default
        private Integer maxMemoryTokens = 7_500;
        @Builder.Default
        private Integer maxRagTokens = 5_000;

        /*
         * ────────── ⑤ 검색 모드 ──────────
         *
         * Controls how the backend performs web search. When set to AUTO (default)
         * the system will decide dynamically whether to execute a web query based on
         * the question complexity and the assistant’s own analysis. OFF disables
         * live web search entirely. FORCE_LIGHT performs a single lightweight
         * search with a short snippet limit, while FORCE_DEEP runs a full multi-step
         * search with self-ask decomposition and verification. This field is
         * independent of the existing retrieval.mode setting and gives the user
         * explicit control over the search plugin. See {@link
         * com.example.lms.gptsearch.dto.SearchMode} for mode definitions.
         */
        @JsonProperty("searchMode")
        @Builder.Default
        private com.example.lms.gptsearch.dto.SearchMode searchMode = com.example.lms.gptsearch.dto.SearchMode.AUTO;

        /**
         * Optional list of preferred web search providers. When null or empty the
         * system will choose from its configured provider list (e.g. Bing,
         * Tavily, GoogleCSE). Clients can specify multiple provider IDs such as
         * ["BING", "TAVILY"] to query multiple backends in parallel. This
         * attribute is ignored when searchMode is OFF.
         */
        @JsonProperty("webProviders")
        @Builder.Default
        private java.util.List<String> webProviders = java.util.Arrays.asList("NAVER", "BRAVE");

        /* ────────── ⑤ 검색 개수(top-k) ────────── */
        /** 프런트 슬라이더로 넘어오는 웹 스니펫 개수(네이버 display와 동기) */
        @JsonProperty("webTopK")
        @Builder.Default
        private Integer webTopK = 8;

        /**
         * 검색 쿼리 개수/확장 힌트 (UAW Autolearn 등에서 활용).
         * 기본값 0 → 시스템 기본 동작.
         */
        @JsonProperty("searchQueries")
        @Builder.Default
        private Integer searchQueries = 0;

        /*
         * ───── 축적 검색 모드 ─────
         *
         * When true the web retrieval layer will perform a broad "accumulation" search.
         * In accumulation mode the system will fan out to all available providers,
         * disable strict domain filtering and apply a looser relatedness cutoff on
         * full page content. This setting is only honoured when web search is
         * enabled (useWebSearch=true) and precision search is requested. Defaults
         * to false.
         */
        @JsonProperty("accumulation")
        @Builder.Default
        private Boolean accumulation = false;

        /**
         * Optional list of role or source scope hints. Clients may specify
         * categories such as "OFFICIAL", "WIKI", "NEWS" or "BLOG" to bias the
         * search providers or ranking heuristics. When null or empty no
         * additional scope hints are applied. The list is passed verbatim to
         * downstream handlers via the query metadata.
         */
        @JsonProperty("roleScope")
        @Builder.Default
        private java.util.List<String> roleScope = null;

        /**
         * Optional domain allowlist profile name. When specified and the
         * {@code officialSourcesOnly} flag is true, the web search handler will
         * restrict results to the domains associated with the given profile.
         * Common profile names include "official" (the default government and
         * academic whitelist) and "jul14" (a relaxed allowlist including
         * popular blogs and forums). When null no specific profile is
         * selected and the handler falls back to the default profile defined
         * in the application configuration.
         */
        @JsonProperty("domainProfile")
        private String domainProfile;

        /*
         * Advanced retrieval controls
         *
         * If true, the retrieval layer should filter results to only those
         * originating from trusted, official domains. The list of official
         * domains is configured via the property `search.official.domains`.
         * When false or null, all domains are considered and ranked.
         */
        @Builder.Default
        private Boolean officialSourcesOnly = false;

        /**
         * User-selected search scopes. Allowed values include "web" to
         * enable live/hybrid web search and "documents" to enable vector
         * database retrieval. When the list is null or empty, both
         * retrieval modes are assumed enabled. The frontend encodes this
         * field as a JSON array of strings.
         */
        @Builder.Default
        private java.util.List<String> searchScopes = java.util.Collections.emptyList();

        /**
         * 정밀 검색(대용량 웹 스캔) 실행 여부. When true the web search layer will
         * retrieve the full bodies of the top N results and combine them into
         * a single context for summarization. Defaults to false.
         */
        @Builder.Default
        private Boolean precisionSearch = false;

        /**
         * 정밀 검색 시 스캔할 URL 개수. If null, the system will fall back to
         * the standard {@code webTopK} value or a default of 10. Only used
         * when {@code precisionSearch} is true.
         */
        private Integer precisionTopK;

        /**
         * Optional base64 encoded image payload. If provided, downstream
         * services may invoke multimodal models to extract information
         * from the image. The payload should not include any data URI
         * prefix (e.g., "data:image/png;base64,") and consist solely of
         * the raw base64 data. When null, no image was attached.
         */
        private String imageBase64;

        /** Optional list of attachment IDs associated with this message */
        private java.util.List<String> attachmentIds;

        /* ───── Jackson Setter : 부작용 제거 ───── */
        /**
         * Set whether to enable RAG (vector retrieval).
         *
         * The previous implementation implicitly enabled the web search flag
         * whenever RAG was turned on. This coupling caused the web search
         * toggle to flip on unexpectedly whenever the client enabled RAG.
         * To honour the user's explicit intent, the setter now only updates
         * the {@code useRag} field and does not modify {@code useWebSearch}.
         *
         * @param useRag true to enable retrieval via the vector database, false
         *               otherwise
         */
        public void setUseRag(java.lang.Boolean useRag) {
                // Assign the wrapper field directly. Do not toggle web search implicitly.
                this.useRag = useRag;
        }

        public void setUseWebSearch(java.lang.Boolean useWebSearch) {
                this.useWebSearch = useWebSearch;
                // When the caller explicitly sets a value (even null), mark the explicit flag
                // accordingly.
                this.webSearchExplicit = (useWebSearch != null);
        }

        /**
         * Indicates that the client explicitly toggled the web search flag. When
         * this field is {@code Boolean.TRUE} the controller will override any
         * configured defaults and force {@code useWebSearch} to {@code true}.
         * A {@code null} value denotes that the client did not specify the
         * web search toggle. This flag is not serialised to the front end.
         */
        @Getter(AccessLevel.NONE)
        @Setter(AccessLevel.NONE)
        @Builder.Default
        private transient java.lang.Boolean webSearchExplicit = null;
        /**
         * Guard/profile mode for this request (e.g. SAFE, BRAVE, ZERO_BREAK, WILD).
         * Optional; when null the server may derive a profile from plan or defaults.
         */
        private String profile;

        /**
         * Guard level hint controlling safety gates (e.g. HIGH, NORMAL, LOW).
         */
        private String guardLevel;

        /**
         * Memory profile for the session (e.g. STRICT, BALANCED, CREATIVE).
         * When null, the server will use the default profile.
         */
        private com.example.lms.domain.enums.MemoryProfile memoryProfile;

        /*
         * ----------------------------------------------------------------------
         * Custom getters to preserve boolean semantics when using nullable flags.
         * When the underlying wrapper is {@code null}, the default is considered
         * {@code false}. These methods allow existing code calling
         * {@code isUseRag()}, {@code isUseWebSearch()}, {@code isPolish()} and
         * {@code isWebSearchExplicit()} to continue to work correctly after
         * migrating the corresponding fields to nullable types.
         */

        /**
         * Return whether vector retrieval is enabled. This returns
         * {@code true} only when the {@link #useRag} field is {@link Boolean#TRUE}.
         *
         * @return true if useRag is explicitly true; otherwise false
         */
        public boolean isUseRag() {
                return java.lang.Boolean.TRUE.equals(this.useRag);
        }

        // Server-side only (controller/builder). Keep it out of JSON payloads.
        @JsonIgnore
        public java.lang.Boolean getWebSearchExplicit() {
                return this.webSearchExplicit;
        }

        /**
         * Return whether web search is enabled. This returns {@code true}
         * only when {@link #useWebSearch} is {@link Boolean#TRUE}.
         *
         * @return true when web search is enabled
         */
        public boolean isUseWebSearch() {
                return java.lang.Boolean.TRUE.equals(this.useWebSearch);
        }

        /**
         * Indicate whether answer polishing has been requested. When the
         * {@link #polish} flag is {@code null} or {@code false} this returns
         * {@code false}.
         *
         * @return true if polish is explicitly true
         */
        public boolean isPolish() {
                return java.lang.Boolean.TRUE.equals(this.polish);
        }

        /**
         * Returns whether the client explicitly toggled web search. When
         * {@code true} the controller will override configured defaults.
         *
         * @return true if the client explicitly set the web search flag
         */
        public boolean isWebSearchExplicit() {
                return java.lang.Boolean.TRUE.equals(this.webSearchExplicit);
        }

        /* ────────── ⑤ 대화 이력 DTO ────────── */
        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @lombok.Builder
        public static class Message {
                private String role;
                private String content;
        }

        private String normalizeModelId(String modelId) {
                if (modelId == null || modelId.isBlank())
                        return null; // delegate to routing/config
                return modelId.trim();
        }

}