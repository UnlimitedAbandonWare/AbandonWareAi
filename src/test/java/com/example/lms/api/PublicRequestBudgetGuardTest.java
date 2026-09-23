package com.example.lms.api;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.SearchPolicyDecision;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.ChatWorkflow;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.fusion.ReciprocalRankFuser;
import com.example.lms.service.rag.handler.RetrievalHandler;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.core.io.DefaultResourceLoader;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublicRequestBudgetGuardTest {

    @AfterEach
    void clearTrace() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @Test
    void normalBrowserChatPayloadAndExactMessageBoundaryAreAccepted() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        ChatRequestDto browserPayload = ChatRequestDto.builder()
                .message("hello")
                .model("qwen3:8b")
                .useRag(true)
                .useWebSearch(true)
                .searchMode(SearchMode.AUTO)
                .build();

        assertDoesNotThrow(() -> guard.validateChat(browserPayload));
        assertEquals("chat", TraceStore.get("public.request.budget.endpoint"));
        assertEquals(0, TraceStore.get("public.request.budget.rejectReason") == null ? 0 : 1);
        assertEquals("NONE", TraceStore.get("public.request.budget.imageMediaType"));

        browserPayload.setMessage("x".repeat(guard.getMaxMessageChars()));
        assertDoesNotThrow(() -> guard.validateChat(browserPayload));

        browserPayload.setMessage("x".repeat(guard.getMaxMessageChars() + 1));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_message_too_large",
                () -> guard.validateChat(browserPayload));
    }

    @Test
    void generationRequiresMessageButExactStreamAttachMayUseBlankMessage() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        ChatRequestDto blank = ChatRequestDto.builder().message("  ").build();

        assertRejection(HttpStatus.BAD_REQUEST, "chat_message_required",
                () -> guard.validateChat(blank));
        assertDoesNotThrow(() -> guard.validateChatForStream(blank, true));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_message_required",
                () -> guard.validateChatForStream(blank, false));
    }

    @Test
    void malformedChatJsonUsesStablePublicReasonCode() {
        ChatApiController controller = mock(ChatApiController.class, Answers.CALLS_REAL_METHODS);

        var response = controller.publicRequestBodyInvalid(
                new HttpMessageNotReadableException("invalid public chat json"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("chat_body_invalid", response.getBody().get("reasonCode"));
    }

    @Test
    void chatRejectsNonFiniteOutOfRangeAndAggregateModelBudgets() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();

        assertRejection(HttpStatus.BAD_REQUEST, "chat_temperature_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").temperature(Double.NaN).build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_top_p_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").topP(Double.POSITIVE_INFINITY).build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_frequency_penalty_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").frequencyPenalty(-2.01d).build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_max_tokens_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").maxTokens(Integer.MAX_VALUE).build()));

        guard.setMaxTotalTokenBudget(10);
        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "chat_model_budget_exceeded",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q")
                        .maxTokens(5)
                        .maxMemoryTokens(4)
                        .maxRagTokens(2)
                        .build()));
    }

    @Test
    void chatRejectsOversizedHistoryCollectionsAttachmentsAndFanout() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        List<ChatRequestDto.Message> atBoundary = new ArrayList<>();
        for (int i = 0; i < guard.getMaxHistoryItems(); i++) {
            atBoundary.add(new ChatRequestDto.Message("user", "ok"));
        }
        assertDoesNotThrow(() -> guard.validateChat(ChatRequestDto.builder()
                .message("q")
                .history(atBoundary)
                .build()));

        List<ChatRequestDto.Message> overBoundary = new ArrayList<>(atBoundary);
        overBoundary.add(new ChatRequestDto.Message("assistant", "too many"));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_history_count_exceeded",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").history(overBoundary).build()));

        guard.setMaxImageBase64Chars(12);
        assertDoesNotThrow(() -> guard.validateChat(ChatRequestDto.builder()
                .message("q")
                .imageBase64(encodedImage("image/png"))
                .imageMediaType("image/png")
                .build()));
        TraceStore.clear();
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_image_too_large",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q")
                        .imageBase64("QUJDREVGR0hJSg==")
                        .imageMediaType("image/png")
                        .build()));
        assertNull(TraceStore.get("public.request.budget.imageDecodedBytes"));

        guard.setMaxAttachmentIds(1);
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_attachment_count_exceeded",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q").attachmentIds(List.of("a", "b")).build()));

        guard.setMaxRetrievalWork(7);
        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "chat_retrieval_budget_exceeded",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q")
                        .useWebSearch(true)
                        .webProviders(List.of("NAVER", "BRAVE"))
                        .webTopK(8)
                        .build()));
    }

    @Test
    void chatAcceptsAllowlistedImageMediaTypesAndDefaultsBlankTypeToPng() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();

        for (String mediaType : List.of("image/png", "image/jpeg", "image/webp")) {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("describe the synthetic image")
                    .imageBase64(encodedImage(mediaType))
                    .imageMediaType(mediaType)
                    .build();

            assertDoesNotThrow(() -> guard.validateChat(request), mediaType);
            assertEquals(mediaType, request.resolvedImageMediaType());
            assertEquals(encodedImage(mediaType).length(),
                    TraceStore.get("public.request.budget.imageEncodedChars"));
            assertEquals(imageBytes(mediaType).length,
                    TraceStore.get("public.request.budget.imageDecodedBytes"));
            assertEquals(expectedImageMediaType(mediaType),
                    TraceStore.get("public.request.budget.imageMediaType"));
            assertEquals(Boolean.TRUE, TraceStore.get("public.request.budget.imagePayloadRedacted"));
        }

        ChatRequestDto defaultPng = ChatRequestDto.builder()
                .message("describe the synthetic image")
                .imageBase64(encodedImage("image/png"))
                .imageMediaType("  ")
                .build();
        assertDoesNotThrow(() -> guard.validateChat(defaultPng));
        assertEquals("image/png", defaultPng.resolvedImageMediaType());
        assertEquals("PNG", TraceStore.get("public.request.budget.imageMediaType"));
    }

    @Test
    void chatRejectsImageSignatureMismatchAndTruncatedHeadersWithFixedCode() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();

        assertRejection(HttpStatus.BAD_REQUEST, "image_signature_mismatch",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q")
                        .imageBase64(encodedImage("image/jpeg"))
                        .imageMediaType("image/png")
                        .build()));
        assertRejection(HttpStatus.BAD_REQUEST, "image_signature_mismatch",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q")
                        .imageBase64(Base64.getEncoder().encodeToString(new byte[] {
                                'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B'
                        }))
                        .imageMediaType("image/webp")
                        .build()));
        assertRejection(HttpStatus.BAD_REQUEST, "image_signature_mismatch",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q")
                        .imageBase64(Base64.getEncoder().encodeToString(new byte[] {
                                (byte) 0x89, 0x50, 0x4e
                        }))
                        .imageMediaType("image/png")
                        .build()));
    }

    @Test
    void chatRejectsMalformedEmptyUnsupportedAndDataUriImagePayloadsWithFixedCodes() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();

        assertRejection(HttpStatus.BAD_REQUEST, "chat_image_base64_invalid",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q").imageBase64("%%%not-base64%%%").build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_image_empty",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q").imageBase64("").build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_image_media_type_unsupported",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q").imageBase64("AA==").imageMediaType("image/gif").build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_image_media_type_unsupported",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q")
                        .imageBase64(encodedImage("image/png"))
                        .imageMediaType("image/png;charset=utf-8")
                        .build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_image_data_uri_not_allowed",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q").imageBase64("data:image/png;base64,AA==").build()));

        assertEquals(Boolean.TRUE, TraceStore.get("public.request.budget.imagePayloadRedacted"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("%%%not-base64%%%"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("data:image/png;base64,AA=="));
    }

    private static String encodedImage(String mediaType) {
        return Base64.getEncoder().encodeToString(imageBytes(mediaType));
    }

    private static byte[] imageBytes(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
            case "image/webp" -> new byte[] {
                    'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'
            };
            default -> new byte[] {
                    (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            };
        };
    }

    private static String expectedImageMediaType(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> "JPEG";
            case "image/webp" -> "WEBP";
            default -> "PNG";
        };
    }

    @Test
    void chatAccountsForLiveHybridRescueBranchesIndependentOfProviderHints() {
        String plain = "weather tomorrow";
        String explicitDomain = "Use example.com as the official evidence source.";
        String twoDomains = "Compare OpenAI Responses API and Supabase MCP using official/external sources only.";
        String threeDomains = "Compare the latest OpenAI changelog and Supabase MCP using official/external sources only.";
        assertEquals(1, ChatApiController.maxWebSearchCallsPerPhase(plain));
        assertEquals(2, ChatApiController.maxWebSearchCallsPerPhase(explicitDomain));
        assertEquals(6, ChatApiController.maxWebSearchCallsPerPhase(twoDomains));
        assertEquals(8, ChatApiController.maxWebSearchCallsPerPhase(threeDomains));

        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxProviderWork(512);
        ChatRequestDto deep = ChatRequestDto.builder()
                .message(threeDomains)
                .useRag(false)
                .useWebSearch(true)
                .webProviders(List.of("BOGUS"))
                .webTopK(8)
                .searchMode(SearchMode.FORCE_DEEP)
                .build();
        assertDoesNotThrow(() -> guard.validateChat(deep));
        assertEquals(512L, TraceStore.get("public.request.budget.providerWork"));
        assertEquals(2, TraceStore.get("public.request.budget.effectiveProviderCount"));

        guard.setMaxProviderWork(511);
        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "chat_provider_budget_exceeded",
                () -> guard.validateChat(deep));

        ChatRequestDto off = deep.toBuilder().searchMode(SearchMode.OFF).build();
        assertDoesNotThrow(() -> guard.validateChat(off));
        assertEquals(0L, TraceStore.get("public.request.budget.providerWork"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(threeDomains));
    }

    @Test
    void chatProjectedBudgetChargesPolicyPlanAndExtremeZExpansion() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        ReflectionTestUtils.setField(guard, "planHintApplier", applier);
        guard.setMaxRetrievalWork(432);
        guard.setMaxProviderWork(10_000);
        ChatRequestDto request = ChatRequestDto.builder()
                .message("recall this topic with bounded evidence")
                .useRag(false)
                .useWebSearch(true)
                .webTopK(8)
                .searchQueries(0)
                .searchMode(SearchMode.AUTO)
                .build();

        assertDoesNotThrow(() -> guard.validateChatEffective(request));
        assertDoesNotThrow(() -> guard.validateChatProjected(
                request, applier.load("brave.v1"), true, false));
        assertEquals(432L, TraceStore.get("public.request.budget.retrievalWork"));
        assertEquals(24L, TraceStore.get("public.request.budget.branchCount"));
        assertEquals(18, TraceStore.get("public.request.budget.effectiveTopK"));
        assertEquals(2_592L, TraceStore.get("public.request.budget.providerWork"));

        guard.setMaxRetrievalWork(431);
        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "chat_retrieval_budget_exceeded",
                () -> guard.validateChatProjected(request, applier.load("brave.v1"), true, false));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "burst offset={0} projectedWork={2}")
    @org.junit.jupiter.params.provider.CsvSource({"-1,22,396,2376,true", "0,24,432,2592,true",
            "1,26,468,2808,false"})
    void shippedQueryBurstCountChangesActualProjectedAdmission(int offset, long expectedBranches,
            long expectedWork, long expectedProviderWork, boolean acceptedAtShippedBudget) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/brave.v1.yaml"),
                StandardCharsets.UTF_8));
        var originalKnobs = original.path("plan").path("overrides").path("knobs");
        int shippedCount = originalKnobs.path("expand.queryBurst.count").asInt(-1);
        assertEquals(12, shippedCount, "selected resource integration baseline");
        int selectedCount = shippedCount + offset;
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("plan").path("overrides").path("knobs"))
                .put("expand.queryBurst.count", selectedCount);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("plan").path("overrides").path("knobs"))
                .put("expand.queryBurst.count", shippedCount);
        assertEquals(original, restored, "fixture changes only the authored query-burst count");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/brave.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "brave.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new PlanHintApplier(offset == 0 ? new DefaultResourceLoader() : resources);
        var plan = applier.load("brave.v1");
        assertEquals("brave.v1", plan.planId());
        assertEquals(selectedCount, plan.queryBurstCount());
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        ReflectionTestUtils.setField(guard, "planHintApplier", applier);
        guard.setMaxProviderWork(10_000);
        ChatRequestDto request = ChatRequestDto.builder()
                .message("recall this topic with bounded evidence").useRag(false).useWebSearch(true)
                .webTopK(8).searchQueries(0).searchMode(SearchMode.AUTO).build();
        guard.setMaxRetrievalWork((int) expectedWork);
        assertDoesNotThrow(() -> guard.validateChatProjected(request, plan, true, false));
        assertEquals(expectedBranches, TraceStore.get("public.request.budget.branchCount"));
        assertEquals(expectedWork, TraceStore.get("public.request.budget.retrievalWork"));
        assertEquals(expectedProviderWork, TraceStore.get("public.request.budget.providerWork"));
        assertEquals(18, TraceStore.get("public.request.budget.effectiveTopK"));
        guard.setMaxRetrievalWork((int) expectedWork - 1);
        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "chat_retrieval_budget_exceeded",
                () -> guard.validateChatProjected(request, plan, true, false));
        guard.setMaxRetrievalWork(432);
        if (acceptedAtShippedBudget) {
            assertDoesNotThrow(() -> guard.validateChatProjected(request, plan, true, false));
        } else {
            assertRejection(HttpStatus.TOO_MANY_REQUESTS, "chat_retrieval_budget_exceeded",
                    () -> guard.validateChatProjected(request, plan, true, false));
        }
        System.out.printf("TBL07_COUNT count=%d branches=%d retrievalWork=%d providerWork=%d acceptedAtShippedBudget=%s%n",
                selectedCount, expectedBranches, expectedWork, expectedProviderWork, acceptedAtShippedBudget);
    }

    @Test
    void chatProjectedPolicyFailureLeavesRedactedFallbackBreadcrumbAndResetsNextRequest() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxRetrievalWork(1_000_000);
        guard.setMaxProviderWork(1_000_000);
        SearchPolicyEngine failingPolicy = new SearchPolicyEngine() {
            @Override
            public SearchPolicyDecision decide(String query, Map<String, Object> metaHints) {
                throw new IllegalStateException("POLICY_FAILURE_DETAIL_MUST_NOT_LEAK");
            }
        };
        ReflectionTestUtils.setField(guard, "searchPolicyEngine", failingPolicy);
        ChatRequestDto request = ChatRequestDto.builder()
                .message("bounded projected web request")
                .useWebSearch(true)
                .webTopK(1)
                .searchQueries(0)
                .searchMode(SearchMode.AUTO)
                .build();

        assertDoesNotThrow(() -> guard.validateChatProjected(request, null, true, false));
        assertEquals(Boolean.TRUE,
                TraceStore.get("public.request.budget.searchPolicyFallback"));
        assertEquals("IllegalStateException",
                TraceStore.get("public.request.budget.searchPolicyFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll())
                .contains("POLICY_FAILURE_DETAIL_MUST_NOT_LEAK"));

        ReflectionTestUtils.setField(guard, "searchPolicyEngine", new SearchPolicyEngine());
        assertDoesNotThrow(() -> guard.validateChatProjected(request, null, true, false));
        assertNull(TraceStore.get("public.request.budget.searchPolicyFallback"));
        assertNull(TraceStore.get("public.request.budget.searchPolicyFallback.errorType"));
    }

    @Test
    void chatProjectedProviderWorkIncludesHybridInternalEnglishAndKoreanLadders() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxRetrievalWork(1_000);
        guard.setMaxProviderWork(10_000);

        ChatRequestDto english = ChatRequestDto.builder()
                .message("ordinary evidence request with enough words for balanced search")
                .useWebSearch(true)
                .webTopK(8)
                .searchMode(SearchMode.AUTO)
                .build();
        guard.validateChatProjected(english, null, true, false);
        assertEquals(480L, TraceStore.get("public.request.budget.providerWork"));

        ChatRequestDto korean = english.toBuilder()
                .message("일반적인 근거 검색 요청을 충분히 긴 문장으로 작성합니다")
                .build();
        guard.validateChatProjected(korean, null, true, false);
        long koreanBranches = ((Number) TraceStore.get("public.request.budget.branchCount")).longValue();
        assertEquals(koreanBranches * 8L * 12L,
                TraceStore.get("public.request.budget.providerWork"));
    }

    @Test
    void chatProjectedBudgetChargesFutureTechWebOverride() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxRetrievalWork(7);
        guard.setMaxProviderWork(10_000);
        ChatRequestDto request = ChatRequestDto.builder()
                .message("What are the Galaxy Fold 8 leaks?")
                .useWebSearch(false)
                .useRag(false)
                .webTopK(8)
                .searchMode(SearchMode.AUTO)
                .build();

        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "chat_retrieval_budget_exceeded",
                () -> guard.validateChatProjected(request, null, false, false));
        assertEquals(80L, TraceStore.get("public.request.budget.retrievalWork"));
    }

    @Test
    void chatTraceSeparatesMessageHistoryAndAttachmentCountsWithoutContent() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        String sentinel = "private-shape-sentinel";
        ChatRequestDto request = ChatRequestDto.builder()
                .message(sentinel)
                .history(List.of(new ChatRequestDto.Message("user", "history")))
                .attachmentIds(List.of("a", "b"))
                .build();

        guard.validateChat(request);

        assertEquals(1L, TraceStore.get("public.request.budget.messageCount"));
        assertEquals(1L, TraceStore.get("public.request.budget.historyCount"));
        assertEquals(2L, TraceStore.get("public.request.budget.attachmentCount"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(sentinel));
    }

    @Test
    void chatAccountsForAutoDeepPrefixAndAllowsZeroTopKOnInactiveAxes() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxRetrievalWork(150);

        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "chat_retrieval_budget_exceeded",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("DEEP 검색 점검: bounded probe")
                        .searchMode(SearchMode.AUTO)
                        .useWebSearch(true)
                        .webProviders(List.of("BRAVE"))
                        .webTopK(100)
                        .build()));

        assertDoesNotThrow(() -> guard.validateChat(ChatRequestDto.builder()
                .message("no retrieval")
                .useWebSearch(false)
                .useRag(false)
                .precisionSearch(false)
                .webTopK(0)
                .precisionTopK(0)
                .build()));
    }

    @Test
    void chatBoundsSystemPromptHistoryItemsTraitsProvidersScopesAndAttachmentIds() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();

        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_system_prompt_too_large",
                () -> guard.validateChat(ChatRequestDto.builder()
                        .message("q").systemPrompt("s".repeat(16_385)).build()));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_history_content_too_large",
                () -> guard.validateChat(ChatRequestDto.builder().message("q")
                        .history(List.of(new ChatRequestDto.Message("user", "h".repeat(16_385))))
                        .build()));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_trait_too_large",
                () -> guard.validateChat(ChatRequestDto.builder().message("q")
                        .traits(List.of("t".repeat(257))).build()));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_provider_count_exceeded",
                () -> guard.validateChat(ChatRequestDto.builder().message("q")
                        .webProviders(java.util.Collections.nCopies(9, "BRAVE")).build()));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_search_scope_count_exceeded",
                () -> guard.validateChat(ChatRequestDto.builder().message("q")
                        .searchScopes(java.util.Collections.nCopies(9, "web")).build()));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_attachment_id_too_large",
                () -> guard.validateChat(ChatRequestDto.builder().message("q")
                        .attachmentIds(List.of("a".repeat(257))).build()));
    }

    @Test
    void chatRejectsAllNumericBoundariesWithoutSilentClamping() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();

        assertRejection(HttpStatus.BAD_REQUEST, "chat_temperature_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").temperature(2.01d).build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_top_p_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").topP(-0.01d).build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_presence_penalty_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").presencePenalty(2.01d).build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_web_top_k_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").webTopK(101).build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_precision_top_k_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").precisionTopK(-1).build()));
        assertRejection(HttpStatus.BAD_REQUEST, "chat_search_queries_invalid",
                () -> guard.validateChat(ChatRequestDto.builder().message("q").searchQueries(9).build()));
    }

    @Test
    void ragRejectsMissingInvalidOversizedAndAggregateRequests() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();

        assertRejection(HttpStatus.BAD_REQUEST, "rag_query_required", () -> guard.validateRag(null));
        QueryRequest blank = new QueryRequest();
        blank.query = "  ";
        assertRejection(HttpStatus.BAD_REQUEST, "rag_query_required", () -> guard.validateRag(blank));

        QueryRequest boundary = new QueryRequest();
        boundary.query = "q".repeat(guard.getMaxQueryChars());
        assertDoesNotThrow(() -> guard.validateRag(boundary));
        boundary.query += "q";
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "rag_query_too_large",
                () -> guard.validateRag(boundary));

        QueryRequest invalidNumber = new QueryRequest();
        invalidNumber.query = "q";
        invalidNumber.diversityLambda = Double.NaN;
        assertRejection(HttpStatus.BAD_REQUEST, "rag_diversity_lambda_invalid",
                () -> guard.validateRag(invalidNumber));

        QueryRequest invalidTopK = new QueryRequest();
        invalidTopK.query = "q";
        invalidTopK.topK = -1;
        assertRejection(HttpStatus.BAD_REQUEST, "rag_top_k_invalid",
                () -> guard.validateRag(invalidTopK));

        QueryRequest aggregate = new QueryRequest();
        aggregate.query = "q";
        aggregate.enableSelfAsk = true;
        aggregate.deepResearch = true;
        aggregate.aggressive = true;
        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "rag_retrieval_budget_exceeded",
                () -> guard.validateRag(aggregate));
    }

    @Test
    void ragRejectsAggregateSeedCountWithoutInspectingOrLoggingRawSeedText() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxSeedItems(2);
        QueryRequest request = new QueryRequest();
        request.query = "safe query";
        request.seedCandidates = List.of(new Doc(), new Doc(), new Doc());

        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_count_exceeded",
                () -> guard.validateRag(request));
    }

    @Test
    void ragBoundsSeedFieldsAndMetadataAndSeedOnlyDoesNotChargeDisabledRetrieval() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        Doc oversized = new Doc();
        oversized.snippet = "s".repeat(16_385);
        QueryRequest oversizedSeed = new QueryRequest();
        oversizedSeed.query = "q";
        oversizedSeed.seedCandidates = List.of(oversized);
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_content_too_large",
                () -> guard.validateRag(oversizedSeed));

        Doc metadataHeavy = new Doc();
        metadataHeavy.meta = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) metadataHeavy.meta.put("k" + i, "v");
        QueryRequest metadataRequest = new QueryRequest();
        metadataRequest.query = "q";
        metadataRequest.seedCandidates = List.of(metadataHeavy);
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_metadata_count_exceeded",
                () -> guard.validateRag(metadataRequest));

        Doc nestedMetadata = new Doc();
        nestedMetadata.meta = Map.of("nested", java.util.Collections.nCopies(33, "v"));
        QueryRequest nestedMetadataRequest = new QueryRequest();
        nestedMetadataRequest.query = "q";
        nestedMetadataRequest.seedCandidates = List.of(nestedMetadata);
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_metadata_count_exceeded",
                () -> guard.validateRag(nestedMetadataRequest));

        guard.setMaxRetrievalWork(1);
        QueryRequest seedOnly = new QueryRequest();
        seedOnly.query = "q";
        seedOnly.seedOnly = true;
        seedOnly.seedCandidates = List.of(new Doc());
        seedOnly.topK = 100;
        assertDoesNotThrow(() -> guard.validateRag(seedOnly));

        QueryRequest inactive = new QueryRequest();
        inactive.query = "q";
        inactive.useWeb = false;
        inactive.useVector = false;
        inactive.useKg = false;
        inactive.useBm25 = false;
        inactive.topK = 0;
        inactive.webTopK = 0;
        inactive.vectorTopK = 0;
        inactive.kgTopK = 0;
        assertDoesNotThrow(() -> guard.validateRag(inactive));
    }

    @Test
    void rejectedRagRequestNeverLeavesRawSeedOrQueryInTrace() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        String sentinel = "private-query-seed-sentinel";
        Doc oversized = new Doc();
        oversized.snippet = sentinel + "x".repeat(16_384);
        QueryRequest request = new QueryRequest();
        request.query = sentinel;
        request.seedCandidates = List.of(oversized);

        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_content_too_large",
                () -> guard.validateRag(request));
        org.junit.jupiter.api.Assertions.assertFalse(
                String.valueOf(TraceStore.getAll()).contains(sentinel));
    }

    @Test
    void ragBoundsEveryAxisAndModeMultiplierWithOverflowSafeArithmetic() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        QueryRequest axis = new QueryRequest();
        axis.query = "q";
        axis.webTopK = 101;
        assertRejection(HttpStatus.BAD_REQUEST, "rag_web_top_k_invalid", () -> guard.validateRag(axis));

        QueryRequest overflow = new QueryRequest();
        overflow.query = "q";
        overflow.topK = Integer.MAX_VALUE;
        assertRejection(HttpStatus.BAD_REQUEST, "rag_top_k_invalid", () -> guard.validateRag(overflow));

        QueryRequest normal = new QueryRequest();
        normal.query = "normal query";
        normal.topK = 8;
        assertDoesNotThrow(() -> guard.validateRag(normal));
        assertEquals("rag", TraceStore.get("public.request.budget.endpoint"));
        assertEquals(null, TraceStore.get("public.request.budget.query"));

        ReflectionTestUtils.setField(guard, "ragOrchestratorMode", "SHADOW");
        guard.setMaxRetrievalWork(40);
        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "rag_retrieval_budget_exceeded",
                () -> guard.validateRag(normal));
    }

    @Test
    void ragBudgetsPlanEnabledKgAxisBeforeDelegation() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        ReflectionTestUtils.setField(guard, "planHintApplier",
                new PlanHintApplier(new DefaultResourceLoader()));
        guard.setMaxRetrievalWork(1);
        QueryRequest request = new QueryRequest();
        request.query = "q";
        request.planId = "kg_first.v1";
        request.topK = 0;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;

        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "rag_retrieval_budget_exceeded",
                () -> guard.validateRag(request));
    }

    @Test
    void ragBudgetsPlanRaisedTopKAndSelfAskBeforeDelegation() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        ReflectionTestUtils.setField(guard, "planHintApplier",
                new PlanHintApplier(new DefaultResourceLoader()));
        guard.setMaxRetrievalWork(40);
        QueryRequest request = new QueryRequest();
        request.query = "q";
        request.planId = "brave.v1";
        request.topK = 8;
        request.useWeb = true;
        request.useVector = true;
        request.useKg = false;
        request.useBm25 = false;

        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "rag_retrieval_budget_exceeded",
                () -> guard.validateRag(request));
    }

    @Test
    void ragBudgetsPrimaryWildGraphPolicyBeforeFacadeDelegation() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        ReflectionTestUtils.setField(guard, "ragOrchestratorMode", "PRIMARY");
        guard.setMaxRetrievalWork(39);
        QueryRequest request = new QueryRequest();
        request.query = "q";
        request.planId = "wild";
        request.topK = 1;
        request.useWeb = true;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;

        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "rag_retrieval_budget_exceeded",
                () -> guard.validateRag(request));
        assertEquals(40L, TraceStore.get("public.request.budget.retrievalWork"));
        assertEquals(10, TraceStore.get("public.request.budget.effectiveTopK"));
    }

    @Test
    void allThreeChatGenerationEndpointsRejectBeforeTouchingDependencies() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxMessageChars(4);
        ChatApiController controller = mock(ChatApiController.class, Answers.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "publicRequestBudgetGuard", guard);
        ChatRequestDto oversized = ChatRequestDto.builder().message("12345").build();

        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_message_too_large",
                () -> controller.chatSync(oversized, null, new MockHttpServletRequest()));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_message_too_large",
                () -> controller.chat(oversized, null, new MockHttpServletRequest()));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_message_too_large",
                () -> controller.chatStream(oversized, false, false, null, new MockHttpServletRequest()));
    }

    @Test
    void streamProjectedBudgetRejectsSynchronouslyBeforeRunOrHistoryWork() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        ReflectionTestUtils.setField(guard, "planHintApplier", applier);
        guard.setMaxRetrievalWork(431);
        guard.setMaxProviderWork(10_000);
        SettingsService settings = mock(SettingsService.class);
        ClientOwnerKeyResolver owner = mock(ClientOwnerKeyResolver.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        com.example.lms.service.chat.ChatRunRegistry runs =
                mock(com.example.lms.service.chat.ChatRunRegistry.class);
        when(settings.getAllSettings()).thenReturn(Map.of());
        ChatApiController controller = mock(ChatApiController.class, Answers.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "publicRequestBudgetGuard", guard);
        ReflectionTestUtils.setField(controller, "settingsService", settings);
        ReflectionTestUtils.setField(controller, "ownerKeyResolver", owner);
        ReflectionTestUtils.setField(controller, "historyService", history);
        ReflectionTestUtils.setField(controller, "runRegistry", runs);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Jammini-Mode", "brave.v1");
        ChatRequestDto request = ChatRequestDto.builder()
                .message("recall this topic with bounded evidence")
                .useWebSearch(true)
                .useRag(false)
                .webTopK(8)
                .searchMode(SearchMode.AUTO)
                .build();

        assertRejection(HttpStatus.TOO_MANY_REQUESTS, "chat_retrieval_budget_exceeded",
                () -> controller.chatStream(request, false, false, null, http));
        verifyNoInteractions(owner, history, runs);
    }

    @Test
    void allThreeChatGenerationEndpointsRejectInvalidEffectiveSettingsBeforeOwnerWork() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        SettingsService settings = mock(SettingsService.class);
        ClientOwnerKeyResolver owner = mock(ClientOwnerKeyResolver.class);
        when(settings.getAllSettings()).thenReturn(Map.of(
                SettingsService.KEY_OPENAI_MODEL, "m".repeat(257)));
        ChatApiController controller = mock(ChatApiController.class, Answers.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "publicRequestBudgetGuard", guard);
        ReflectionTestUtils.setField(controller, "settingsService", settings);
        ReflectionTestUtils.setField(controller, "ownerKeyResolver", owner);
        ChatRequestDto request = ChatRequestDto.builder().message("valid raw message").build();

        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_model_too_large",
                () -> controller.chatSync(request, null, new MockHttpServletRequest()));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_model_too_large",
                () -> controller.chat(request, null, new MockHttpServletRequest()));
        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_model_too_large",
                () -> controller.chatStream(request, false, false, null, new MockHttpServletRequest()));
        verifyNoInteractions(owner);
    }

    @Test
    void sessionAttachmentExpansionUsesOnlyOneItemOfLookaheadBeforeRejecting() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxAttachmentIds(2);
        SettingsService settings = mock(SettingsService.class);
        ClientOwnerKeyResolver owner = mock(ClientOwnerKeyResolver.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatService chat = mock(ChatService.class);
        AttachmentService attachments = mock(AttachmentService.class);
        when(settings.getAllSettings()).thenReturn(Map.of());
        when(owner.ownerKey()).thenReturn("owner-a");
        when(attachments.findIdsBySession(
                org.mockito.ArgumentMatchers.eq("7"),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.any(com.example.lms.service.AttachmentOwnerIdentity.class)))
                .thenReturn(List.of("a", "b", "c"));
        ChatApiController controller = mock(ChatApiController.class, Answers.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "publicRequestBudgetGuard", guard);
        ReflectionTestUtils.setField(controller, "settingsService", settings);
        ReflectionTestUtils.setField(controller, "ownerKeyResolver", owner);
        ReflectionTestUtils.setField(controller, "historyService", history);
        ReflectionTestUtils.setField(controller, "chatService", chat);
        ReflectionTestUtils.setField(controller, "attachmentService", attachments);
        ChatRequestDto request = ChatRequestDto.builder()
                .message("summarize the uploaded file")
                .sessionId(7L)
                .build();

        assertRejection(HttpStatus.PAYLOAD_TOO_LARGE, "chat_attachment_count_exceeded",
                () -> controller.chat(request, null, new MockHttpServletRequest()));

        verify(attachments).findIdsBySession(
                org.mockito.ArgumentMatchers.eq("7"),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.any(com.example.lms.service.AttachmentOwnerIdentity.class));
        verifyNoInteractions(chat);
    }

    @Test
    void cancelAndAckRejectOversizeJsonBeforeControllerBinding() throws Exception {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxJsonBodyBytes(8);

        for (String path : List.of("/api/chat/cancel", "/api/chat/ack")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent("123456789".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            guard.doFilter(request, response, chain);

            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), response.getStatus(), path);
            assertNull(chain.getRequest(), path + " must reject before controller binding");
            assertTrue(response.getContentAsString().contains("public_request_body_too_large"));
        }
    }

    @Test
    void publicJsonBodyIsBoundedBeforeControllerDeserialization() throws Exception {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxJsonBodyBytes(8);
        MockHttpServletRequest oversized = new MockHttpServletRequest("POST", "/api/chat");
        oversized.setContentType(MediaType.APPLICATION_JSON_VALUE);
        oversized.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse rejected = new MockHttpServletResponse();

        guard.doFilter(oversized, rejected, new MockFilterChain());

        assertEquals(413, rejected.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(MediaType.APPLICATION_PROBLEM_JSON
                .isCompatibleWith(MediaType.parseMediaType(rejected.getContentType())));
        org.junit.jupiter.api.Assertions.assertTrue(
                rejected.getContentAsString().contains("public_request_body_too_large"));

        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/rag/probe") {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1L; }
        };
        chunked.setContentType(MediaType.APPLICATION_JSON_VALUE);
        chunked.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse chunkedRejected = new MockHttpServletResponse();
        guard.doFilter(chunked, chunkedRejected, new MockFilterChain());
        assertEquals(413, chunkedRejected.getStatus());

        MockHttpServletRequest boundary = new MockHttpServletRequest("POST", "/api/rag/query");
        boundary.setContentType(MediaType.APPLICATION_JSON_VALUE);
        boundary.setContent("12345678".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        guard.doFilter(boundary, accepted, new MockFilterChain());
        assertEquals(200, accepted.getStatus());
    }

    @Test
    void rawBodyByteCountSurvivesTypedAdmissionValidation() throws Exception {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        byte[] body = "{\"query\":\"q\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rag/query");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body);
        QueryRequest query = new QueryRequest();
        query.query = "q";

        guard.doFilter(request, new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> guard.validateRag(query));

        assertEquals(body.length, TraceStore.get("public.request.budget.bodyBytes"));
    }

    @Test
    void invalidPublicBudgetConfigurationFailsClosed() {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxMessageChars(0);
        assertThrows(IllegalStateException.class, guard::validateConfiguration);
    }

    @Test
    void adaptiveHardPolicyNeverRestoresMissingDeniedOrUnresolvableUrls() {
        DomainProfileLoader denyAll = mock(DomainProfileLoader.class);
        List<List<WebDocument>> cases = List.of(
                List.of(new WebDocument(null, "missing url", "private snippet", null, null)),
                List.of(new WebDocument("https://evil.example/one", "denied", "private snippet", null, null)));

        for (List<WebDocument> documents : cases) {
            AdaptiveWebSearchHandler handler = adaptiveHandler(documents, denyAll);
            List<Content> evidence = new ArrayList<>();
            handler.handle(QueryUtils.buildQuery("private hard policy query", Map.of(
                    "useWebSearch", true,
                    "searchMode", "FORCE_LIGHT",
                    "webProviders", "TAVILY",
                    "officialOnly", true,
                    "intent", "FINANCE")), evidence);
            assertEquals(List.of(), evidence);
            assertEquals("hard_policy_filtered_empty",
                    TraceStore.get("retrieval.integrity.emptyReason"));
        }

        AdaptiveWebSearchHandler unavailable = adaptiveHandler(
                List.of(new WebDocument("https://unknown.example/one", "unknown", "private snippet", null, null)),
                null);
        List<Content> evidence = new ArrayList<>();
        unavailable.handle(QueryUtils.buildQuery("private unavailable policy query", Map.of(
                "useWebSearch", true,
                "searchMode", "FORCE_LIGHT",
                "webProviders", "TAVILY",
                "officialOnly", true,
                "intent", "FINANCE")), evidence);
        assertEquals(List.of(), evidence);
        assertEquals("policy_resolver_unavailable",
                TraceStore.get("retrieval.integrity.emptyReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private snippet"));
    }

    @Test
    void hybridInvalidInputAndOuterFailureReturnEmptyInsteadOfSyntheticEvidence() {
        ReciprocalRankFuser fuser = mock(ReciprocalRankFuser.class);
        RetrievalHandler handler = mock(RetrievalHandler.class);
        HybridRetriever retriever = hybridRetriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", true);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");

        assertEquals(List.of(), retriever.retrieveProgressive(" ", "session", 1));
        assertEquals("invalid_input", TraceStore.get("retrieval.integrity.emptyReason"));

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Content> accumulator = invocation.getArgument(1);
            accumulator.add(Content.from("real candidate"));
            return null;
        }).when(handler).handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        when(fuser.fuse(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("fixture failure"));

        assertEquals(List.of(), retriever.retrieveAll(List.of("q"), 1));
        assertEquals("provider_hard_failure", TraceStore.get("retrieval.integrity.emptyReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("real candidate"));
    }

    @Test
    void publicBudgetHeaderCannotExtendOrOverflowServerDeadline() throws Exception {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxTimeBudgetMs(120L);

        for (String header : List.of("invalid-private-budget", "   ", "0", "-1", "121", String.valueOf(Long.MAX_VALUE))) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
            request.addHeader("X-Budget-Ms", header);
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent("{}".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            guard.doFilter(request, response, chain);

            assertEquals(400, response.getStatus(), header);
            assertEquals(null, chain.getRequest(), header);
            assertTrue(response.getContentAsString().contains("public_time_budget_invalid"), header);
            if (header.startsWith("invalid-private")) {
                assertFalse(String.valueOf(TraceStore.getAll()).contains(header),
                        "raw header must not enter trace");
            }
            TraceStore.clear();
        }

        MockHttpServletRequest accepted = new MockHttpServletRequest("POST", "/api/chat");
        accepted.addHeader("X-Budget-Ms", "120");
        accepted.setContentType(MediaType.APPLICATION_JSON_VALUE);
        accepted.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        MockFilterChain acceptedChain = new MockFilterChain();
        guard.doFilter(accepted, acceptedResponse, acceptedChain);
        assertEquals(200, acceptedResponse.getStatus());
        assertTrue(acceptedChain.getRequest() != null);
        long propagatedHeader = Long.parseLong(
                ((jakarta.servlet.http.HttpServletRequest) acceptedChain.getRequest())
                        .getHeader("X-Budget-Ms"));
        assertEquals(120L, propagatedHeader);
        assertEquals(null, TimeBudgetContext.get(), "the outer admission deadline must not leak after the request");
    }

    @Test
    void absentPublicBudgetHeaderUsesCompatibilityDefault() throws Exception {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxTimeBudgetMs(120_000L);
        ReflectionTestUtils.setField(guard, "defaultTimeBudgetMs", 1_500L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Long> observedRemaining = new AtomicReference<>();

        guard.doFilter(request, response, (guardedRequest, guardedResponse) ->
                observedRemaining.set(TimeBudgetContext.get().remainingMillis()));

        assertEquals(200, response.getStatus());
        assertTrue(observedRemaining.get() != null
                && observedRemaining.get() > 0L
                && observedRemaining.get() <= 1_500L);
        assertEquals(1_500L, TraceStore.get("public.request.budget.timeBudgetMs"));
        assertEquals(null, TimeBudgetContext.get());
    }

    @Test
    void downstreamTimeBudgetFilterReusesTheSameAbsoluteDeadline() throws Exception {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxTimeBudgetMs(40L);
        com.abandonware.ai.addons.config.AddonsProperties props =
                new com.abandonware.ai.addons.config.AddonsProperties();
        props.getBudget().setDefaultMs(120_000L);
        com.abandonware.ai.addons.budget.TimeBudgetFilter downstream =
                new com.abandonware.ai.addons.budget.TimeBudgetFilter(props);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.addHeader("X-Budget-Ms", "40");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<TimeBudget> beforeDelay = new AtomicReference<>();
        AtomicBoolean nestedCalled = new AtomicBoolean();

        guard.doFilter(request, response, (guardedRequest, guardedResponse) -> {
            beforeDelay.set(TimeBudgetContext.get());
            try {
                Thread.sleep(80L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            downstream.doFilter(guardedRequest, guardedResponse, (nestedRequest, nestedResponse) -> {
                nestedCalled.set(true);
            });
        });

        assertTrue(beforeDelay.get() != null);
        assertFalse(nestedCalled.get(), "an expired absolute deadline must not be resurrected downstream");
        assertEquals(408, response.getStatus());
        assertTrue(response.getContentAsString().contains("request_deadline_exhausted"));
        assertEquals(null, TimeBudgetContext.get());
    }

    @Test
    void publicPathGuardDoesNotMutateServerWideConnectorTimeout() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/PublicRequestBudgetGuard.java"));

        assertFalse(source.contains("WebServerFactoryCustomizer"));
        assertFalse(source.contains("setConnectionUploadTimeout"));
    }

    @Test
    void standaloneTimeBudgetFilterCannotOverflowItsHardServerCeiling() throws Exception {
        com.abandonware.ai.addons.config.AddonsProperties props =
                new com.abandonware.ai.addons.config.AddonsProperties();
        props.getBudget().setDefaultMs(Long.MAX_VALUE);
        com.abandonware.ai.addons.budget.TimeBudgetFilter filter =
                new com.abandonware.ai.addons.budget.TimeBudgetFilter(props);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/probe");
        request.addHeader("X-Budget-Ms", String.valueOf(Long.MAX_VALUE));
        MockHttpServletResponse response = new MockHttpServletResponse();
        java.util.concurrent.atomic.AtomicLong observedRemaining =
                new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);

        filter.doFilter(request, response, (nestedRequest, nestedResponse) ->
                observedRemaining.set(TimeBudgetContext.get().remainingMillis()));

        assertTrue(observedRemaining.get() > 0L && observedRemaining.get() <= 3_600_000L);
        assertEquals(true, TraceStore.get("timeBudget.header.clamped"));
        assertEquals(null, TimeBudgetContext.get());
    }

    @Test
    void publicDeadlineStartsBeforeBoundedBodyConsumption() throws Exception {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxTimeBudgetMs(40L);
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean delayed = new AtomicBoolean();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat") {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(payload);
                return new jakarta.servlet.ServletInputStream() {
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
                    @Override public int read() {
                        if (delayed.compareAndSet(false, true)) {
                            try {
                                Thread.sleep(80L);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return input.read();
                    }
                };
            }
        };
        request.addHeader("X-Budget-Ms", "40");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(payload);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        guard.doFilter(request, response, chain);

        assertEquals(408, response.getStatus());
        assertEquals(null, chain.getRequest());
        assertTrue(response.getContentAsString().contains("public_request_deadline_exhausted"));
        assertEquals(null, TimeBudgetContext.get());
    }

    @Test
    void slowTrickleBodyCloseFailureCannotReplaceAbsoluteDeadlineResponse() throws Exception {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setMaxTimeBudgetMs(50L);
        AtomicBoolean closed = new AtomicBoolean();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat") {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                return new jakarta.servlet.ServletInputStream() {
                    private int remaining = 20;
                    @Override public boolean isFinished() { return remaining <= 0 || closed.get(); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
                    @Override public int read() {
                        if (isFinished()) return -1;
                        try {
                            Thread.sleep(30L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return -1;
                        }
                        if (closed.get()) return -1;
                        remaining--;
                        return '{';
                    }
                    @Override public void close() throws java.io.IOException {
                        closed.set(true);
                        throw new java.io.IOException("private-close-secret");
                    }
                };
            }
        };
        request.addHeader("X-Budget-Ms", "50");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        long started = System.nanoTime();
        guard.doFilter(request, response, chain);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(408, response.getStatus());
        assertEquals(null, chain.getRequest());
        assertTrue(elapsedMs < 250L, "slow trickle exceeded absolute deadline: " + elapsedMs);
        assertTrue(closed.get(), "deadline must close the in-flight request body");
        assertEquals(true, TraceStore.get("public.request.budget.bodyCloseFailed"));
        assertEquals(1L, TraceStore.get("public.request.budget.bodyCloseFailed.count"));
        assertEquals("IOException", TraceStore.get("public.request.budget.bodyCloseFailed.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("private-close-secret"));
        assertEquals(null, TimeBudgetContext.get());
    }

    @Test
    void bodyReaderWorkerDoesNotInheritRequestThreadContext() throws Exception {
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        guard.setBodyReadWorkers(1);
        guard.setBodyReadQueueCapacity(1);
        InheritableThreadLocal<String> privateRequestContext = new InheritableThreadLocal<>();
        AtomicReference<String> observed = new AtomicReference<>("not-read");
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat") {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(payload);
                return new jakarta.servlet.ServletInputStream() {
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
                    @Override public int read() {
                        observed.compareAndSet("not-read", privateRequestContext.get());
                        return input.read();
                    }
                };
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        privateRequestContext.set("private-request-context");
        try {
            guard.doFilter(request, response, chain);
        } finally {
            privateRequestContext.remove();
            guard.shutdownBodyReadExecutor();
        }

        assertEquals(200, response.getStatus());
        assertEquals(null, observed.get(), "body worker must not inherit caller context");
        assertTrue(chain.getRequest() != null);
    }

    @Test
    void hybridUsesCompletionOrderButPreservesBranchOrderUnderOneDeadline() throws Exception {
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        AtomicBoolean slowInterrupted = new AtomicBoolean();
        RetrievalHandler handler = (query, accumulator) -> {
            if ("slow".equals(query.text())) {
                slowEntered.countDown();
                try {
                    releaseSlow.await();
                } catch (InterruptedException interrupted) {
                    slowInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                accumulator.add(Content.from("slow-real-evidence"));
            } else {
                accumulator.add(Content.from("fast-real-evidence"));
            }
        };
        ReciprocalRankFuser fuser = mock(ReciprocalRankFuser.class);
        when(fuser.fuse(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<List<Content>> buckets = invocation.getArgument(0);
                    return buckets.stream().flatMap(List::stream).toList();
                });
        HybridRetriever retriever = hybridRetriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 2, 2);
        TimeBudgetContext.set(new TimeBudget(250L));

        long started = System.nanoTime();
        List<Content> out;
        try {
            out = retriever.retrieveAll(List.of("slow", "fast"), 2);
        } finally {
            releaseSlow.countDown();
            TimeBudgetContext.clear();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        try {
            assertTrue(slowEntered.await(1, TimeUnit.SECONDS));
            assertTrue(elapsedMs < 1_000L, "one request deadline must bound all joins");
            assertEquals(List.of("fast-real-evidence"), out.stream().map(Content::textSegment)
                    .map(TextSegment::text).toList());
            assertFalse(slowInterrupted.get(), "cooperative cancellation must not poison pooled workers");
            assertEquals("request_deadline_exhausted", TraceStore.get("hybrid.executor.terminalReason"));
            assertEquals(2, TraceStore.get("hybrid.executor.submittedCount"));
            assertEquals(1, TraceStore.get("hybrid.executor.completedCount"));
            assertEquals(0, TraceStore.get("hybrid.executor.providerHardFailureCount"));
        } finally {
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void hybridDeadlineLeavesStartedLoopbackIoActiveUntilResponseRelease() throws Exception {
        CountDownLatch requestSeen = new CountDownLatch(1);
        CountDownLatch inspectAfterReturn = new CountDownLatch(1);
        CountDownLatch peerInspected = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch ioExited = new CountDownLatch(1);
        AtomicBoolean peerStillOpen = new AtomicBoolean();
        AtomicBoolean peerClosedAfterRelease = new AtomicBoolean();
        AtomicBoolean ioInterrupted = new AtomicBoolean();
        var peerRequestNs = new java.util.concurrent.atomic.AtomicLong();
        AtomicReference<java.net.Socket> clientSocket = new AtomicReference<>();
        AtomicReference<java.net.Socket> peerSocket = new AtomicReference<>();
        ExecutorService observer = Executors.newSingleThreadExecutor();
        HybridRetriever retriever = null;
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(3_000);
            var peer = observer.submit(() -> {
                try (java.net.Socket socket = listener.accept()) {
                    peerSocket.set(socket);
                    socket.setSoTimeout(3_000);
                    assertEquals(0x52, socket.getInputStream().read(), "observe an actual synthetic request byte");
                    peerRequestNs.set(System.nanoTime());
                    requestSeen.countDown();
                    assertTrue(inspectAfterReturn.await(3, TimeUnit.SECONDS));
                    socket.setSoTimeout(150);
                    try {
                        socket.getInputStream().read();
                    } catch (java.net.SocketTimeoutException stillConnected) {
                        peerStillOpen.set(true);
                    } finally {
                        peerInspected.countDown();
                    }
                    assertTrue(releaseResponse.await(3, TimeUnit.SECONDS));
                    socket.getOutputStream().write(0x2A);
                    socket.getOutputStream().flush();
                    socket.setSoTimeout(2_000);
                    try {
                        peerClosedAfterRelease.set(socket.getInputStream().read() == -1);
                    } catch (java.net.SocketException peerReset) {
                        peerClosedAfterRelease.set(true);
                    }
                }
                return null;
            });
            dev.langchain4j.rag.content.retriever.ContentRetriever provider = query -> {
                try (java.net.Socket socket = new java.net.Socket()) {
                    clientSocket.set(socket);
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", listener.getLocalPort()), 1_000);
                    socket.setSoTimeout(5_000);
                    socket.getOutputStream().write(0x52);
                    socket.getOutputStream().flush();
                    if (socket.getInputStream().read() != 0x2A) throw new IllegalStateException("unexpected synthetic response");
                    return List.of(Content.from("slow-loopback-evidence"));
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException("controlled loopback I/O failed", failure);
                } finally {
                    ioInterrupted.set(Thread.currentThread().isInterrupted());
                    ioExited.countDown();
                }
            };
            RetrievalHandler handler = (query, accumulator) -> {
                if ("slow-io".equals(query.text())) accumulator.addAll(provider.retrieve(query));
                else accumulator.add(Content.from("fast-loopback-control"));
            };
            ReciprocalRankFuser fuser = mock(ReciprocalRankFuser.class);
            when(fuser.fuse(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked") List<List<Content>> buckets = invocation.getArgument(0);
                        return buckets.stream().flatMap(List::stream).toList();
                    });
            retriever = hybridRetriever(handler, fuser);
            ReflectionTestUtils.setField(retriever, "debugSequential", false);
            ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
            ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 2, 2);
            TimeBudgetContext.set(new TimeBudget(500L));
            long started = System.nanoTime();
            List<Content> result = retriever.retrieveAll(List.of("slow-io", "fast"), 2);
            long returnedAt = System.nanoTime();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(returnedAt - started);
            assertTrue(requestSeen.await(1, TimeUnit.SECONDS));
            assertTrue(peerRequestNs.get() > 0L && peerRequestNs.get() < returnedAt,
                    "the peer must receive real I/O before logical return");
            assertTrue(elapsedMs < 2_000L, "logical request deadline must return before the blocked socket read");
            assertEquals(List.of("fast-loopback-control"), result.stream().map(Content::textSegment).map(TextSegment::text).toList());
            assertEquals("request_deadline_exhausted", TraceStore.get("hybrid.executor.terminalReason"));
            assertEquals(2, TraceStore.get("hybrid.executor.submittedCount"));
            assertEquals(1, TraceStore.get("hybrid.executor.completedCount"));
            assertEquals(1L, ioExited.getCount());
            assertFalse(clientSocket.get().isClosed());
            inspectAfterReturn.countDown();
            assertTrue(peerInspected.await(1, TimeUnit.SECONDS));
            assertTrue(peerStillOpen.get(), "peer must observe a live connection after logical return, not Future state");
            assertEquals(1L, ioExited.getCount());
            releaseResponse.countDown();
            assertTrue(ioExited.await(2, TimeUnit.SECONDS));
            peer.get(2, TimeUnit.SECONDS);
            assertTrue(peerClosedAfterRelease.get(), "peer must observe actual EOF/reset after client cleanup");
            assertTrue(clientSocket.get().isClosed());
            assertFalse(ioInterrupted.get(), "cancel(false) must preserve the started worker interrupt policy");
            System.out.println("F29_IO_COUNTS logicalElapsedMs=" + elapsedMs
                    + " requestBytes=1 connectedAfterDeadline=1 blockedIoAfterDeadline=1 responseBytes=1"
                    + " peerClosedAfterRelease=1 workerExited=1 workerInterrupted=" + ioInterrupted.get());
        } finally {
            inspectAfterReturn.countDown();
            releaseResponse.countDown();
            try {
                for (java.net.Socket socket : new java.net.Socket[]{clientSocket.get(), peerSocket.get()}) {
                    if (socket != null && !socket.isClosed()) socket.close();
                }
            } finally {
                TimeBudgetContext.clear();
                try {
                    if (retriever != null) assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                            retriever, "shutdownRetrievalExecutorForTest", 2_000L));
                } finally {
                    observer.shutdownNow();
                    assertTrue(observer.awaitTermination(2, TimeUnit.SECONDS), "owned peer observer must terminate");
                }
            }
        }
    }

    @Test
    void hybridWorkerDoesNotInheritCallerContext() {
        InheritableThreadLocal<String> privateCallerContext = new InheritableThreadLocal<>();
        AtomicReference<String> observed = new AtomicReference<>("not-called");
        RetrievalHandler handler = (query, accumulator) -> {
            observed.set(privateCallerContext.get());
            accumulator.add(Content.from("clean-evidence"));
        };
        ReciprocalRankFuser fuser = mock(ReciprocalRankFuser.class);
        when(fuser.fuse(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<List<Content>> buckets = invocation.getArgument(0);
                    return buckets.stream().flatMap(List::stream).toList();
                });
        HybridRetriever retriever = hybridRetriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 1, 1);

        privateCallerContext.set("private-caller-context");
        try {
            List<Content> out = retriever.retrieveAll(List.of("q"), 1);
            assertEquals(List.of("clean-evidence"), out.stream()
                    .map(Content::textSegment).map(TextSegment::text).toList());
        } finally {
            privateCallerContext.remove();
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }

        assertEquals(null, observed.get(), "retrieval worker must not inherit caller context");
    }

    @Test
    void hybridPartialSubmissionSaturationRollsBackAndFailsFast() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        RetrievalHandler handler = (query, accumulator) -> {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            accumulator.add(Content.from("late-private-evidence"));
        };
        HybridRetriever retriever = hybridRetriever(handler, new ReciprocalRankFuser());
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 1, 1);

        long started = System.nanoTime();
        List<Content> out = retriever.retrieveAll(List.of("one", "two", "three"), 3);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        try {
            assertEquals(List.of(), out);
            assertTrue(elapsedMs < 500L, "saturation must not wait for accepted slow work");
            assertEquals("executor_saturated", TraceStore.get("hybrid.executor.terminalReason"));
            assertEquals(2, TraceStore.get("hybrid.executor.submittedCount"),
                    "one running slot plus one queue slot must be rolled back");
            assertEquals(0, TraceStore.get("hybrid.executor.completedCount"));
            @SuppressWarnings("unchecked")
            Map<String, Integer> metrics = ReflectionTestUtils.invokeMethod(
                    retriever, "retrievalExecutorMetricsForTest");
            assertTrue(metrics.get("poolSize") <= 1, String.valueOf(metrics));
            assertEquals(0, metrics.get("queued"), "cancelled queued work must be purged");
        } finally {
            release.countDown();
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @Test
    void hybridInterruptedCallerIsCancellationNotProviderFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean workerInterrupted = new AtomicBoolean();
        AtomicBoolean nextWorkerClean = new AtomicBoolean();
        RetrievalHandler handler = (query, accumulator) -> {
            if ("next".equals(query.text())) {
                nextWorkerClean.set(!Thread.currentThread().isInterrupted()
                        && TimeBudgetContext.get() == null
                        && TraceStore.get("private.previous.marker") == null);
                return;
            }
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                workerInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        };
        HybridRetriever retriever = hybridRetriever(handler, new ReciprocalRankFuser());
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 1, 1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        AtomicReference<Map<String, Object>> trace = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                TraceStore.put("private.previous.marker", "must-not-leak");
                retriever.retrieveAll(List.of("one"), 1);
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                trace.set(TraceStore.getAll());
            }
        }, "hybrid-caller-cancel-test");
        caller.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(1_000L);

        try {
            assertFalse(caller.isAlive());
            assertTrue(failure.get() instanceof CancellationException, String.valueOf(failure.get()));
            assertTrue(interruptPreserved.get());
            assertEquals("caller_cancelled", trace.get().get("hybrid.executor.terminalReason"));
            assertFalse("provider_hard_failure".equals(trace.get().get("retrieval.integrity.emptyReason")));
            assertFalse(workerInterrupted.get());
            release.countDown();
            assertTrue(awaitHybridIdle(retriever, 1_000L));
            TraceStore.clear();
            assertEquals(List.of(), retriever.retrieveAll(List.of("next"), 1));
            assertTrue(nextWorkerClean.get(), "cancelled request state must not leak to the reused worker");
        } finally {
            release.countDown();
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @Test
    void hybridProviderCancellationIsNotClassifiedAsHardFailure() {
        RetrievalHandler handler = (query, accumulator) -> {
            throw new CancellationException("provider stop");
        };
        HybridRetriever retriever = hybridRetriever(handler, new ReciprocalRankFuser());
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 1, 1);
        try {
            CancellationException cancelled = assertThrows(CancellationException.class,
                    () -> retriever.retrieveAll(List.of("one"), 1));
            assertEquals("provider stop", cancelled.getMessage());
            assertEquals("provider_cancelled", TraceStore.get("hybrid.executor.terminalReason"));
            assertFalse("provider_hard_failure".equals(
                    TraceStore.get("retrieval.integrity.emptyReason")));
        } finally {
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @Test
    void hybridSequentialDebugModeUsesTheSameBoundedDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RetrievalHandler handler = (query, accumulator) -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            accumulator.add(Content.from("late-private-evidence"));
        };
        HybridRetriever retriever = hybridRetriever(handler, new ReciprocalRankFuser());
        ReflectionTestUtils.setField(retriever, "debugSequential", true);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.setField(retriever, "hybridRequestTimeoutMs", 80L);
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 1, 1);

        long started = System.nanoTime();
        List<Content> out = retriever.retrieveAll(List.of("one"), 1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(), out);
            assertTrue(elapsedMs < 500L, "debug-sequential mode must not bypass the local deadline");
            assertEquals("provider_timeout", TraceStore.get("hybrid.executor.terminalReason"));
        } finally {
            release.countDown();
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @Test
    void hybridDirectBranchListIsHardBoundedBeforeAllocationOrSubmission() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        RetrievalHandler handler = (query, accumulator) -> calls.incrementAndGet();
        HybridRetriever retriever = hybridRetriever(handler, new ReciprocalRankFuser());
        ReflectionTestUtils.setField(retriever, "maxBranches", 2);

        List<Content> out = retriever.retrieveAll(List.of("one", "two", "three"), 3);

        assertEquals(List.of(), out);
        assertEquals(0, calls.get());
        assertEquals("branch_budget_exceeded", TraceStore.get("hybrid.executor.terminalReason"));
        assertEquals(2, TraceStore.get("hybrid.executor.branchLimit"));
    }

    @Test
    void hybridPartialProviderFailureRetainsEvidenceWithoutReportingFullSuccess() {
        RetrievalHandler handler = (query, accumulator) -> {
            if ("bad".equals(query.text())) throw new IllegalStateException("private provider failure");
            accumulator.add(Content.from("good-real-evidence"));
        };
        ReciprocalRankFuser fuser = mock(ReciprocalRankFuser.class);
        when(fuser.fuse(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<List<Content>> buckets = invocation.getArgument(0);
                    return buckets.stream().flatMap(List::stream).toList();
                });
        HybridRetriever retriever = hybridRetriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 2, 2);
        try {
            List<Content> out = retriever.retrieveAll(List.of("bad", "good"), 2);
            assertEquals(List.of("good-real-evidence"), out.stream().map(Content::textSegment)
                    .map(TextSegment::text).toList());
            assertEquals("partial_provider_hard_failure",
                    TraceStore.get("hybrid.executor.terminalReason"));
        } finally {
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @Test
    void hybridCompletionOrderDoesNotChangeFusionBucketIndices() throws Exception {
        CountDownLatch slowEntered = new CountDownLatch(1);
        RetrievalHandler handler = (query, accumulator) -> {
            if ("slow".equals(query.text())) {
                slowEntered.countDown();
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            accumulator.add(Content.from(query.text() + "-evidence"));
        };
        AtomicReference<List<List<String>>> captured = new AtomicReference<>();
        ReciprocalRankFuser fuser = mock(ReciprocalRankFuser.class);
        when(fuser.fuse(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<List<Content>> buckets = invocation.getArgument(0);
                    captured.set(buckets.stream()
                            .map(bucket -> bucket.stream().map(Content::textSegment)
                                    .map(TextSegment::text).toList())
                            .toList());
                    return buckets.stream().flatMap(List::stream).toList();
                });
        HybridRetriever retriever = hybridRetriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 2, 2);
        TimeBudgetContext.set(new TimeBudget(1_000L));
        try {
            List<Content> out = retriever.retrieveAll(List.of("slow", "fast"), 2);
            assertTrue(slowEntered.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(
                    List.of("slow-evidence"),
                    List.of("fast-evidence")), captured.get());
            assertEquals(2, out.size());
            assertEquals("success", TraceStore.get("hybrid.executor.terminalReason"));
        } finally {
            TimeBudgetContext.clear();
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @Test
    void hybridFusionCannotRunPastTheStableLocalDeadline() throws Exception {
        CountDownLatch fusionEntered = new CountDownLatch(1);
        CountDownLatch releaseFusion = new CountDownLatch(1);
        RetrievalHandler handler = (query, accumulator) ->
                accumulator.add(Content.from("real-evidence-before-fusion"));
        ReciprocalRankFuser fuser = mock(ReciprocalRankFuser.class);
        when(fuser.fuse(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    fusionEntered.countDown();
                    try {
                        releaseFusion.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of(Content.from("late-fused-evidence"));
                });
        HybridRetriever retriever = hybridRetriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.setField(retriever, "hybridRequestTimeoutMs", 100L);
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 1, 1);

        long started = System.nanoTime();
        List<Content> out = retriever.retrieveAll(List.of("one"), 1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        try {
            assertTrue(fusionEntered.await(1, TimeUnit.SECONDS));
            assertTrue(elapsedMs < 500L, "fusion must consume only the remaining local budget");
            assertEquals(List.of("real-evidence-before-fusion"), out.stream()
                    .map(Content::textSegment).map(TextSegment::text).toList());
            assertEquals("provider_timeout", TraceStore.get("hybrid.executor.terminalReason"));
        } finally {
            releaseFusion.countDown();
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @Test
    void hybridFusionProviderCancellationKeepsItsOwnTerminalReason() {
        RetrievalHandler handler = (query, accumulator) ->
                accumulator.add(Content.from("real-evidence-before-provider-cancel"));
        ReciprocalRankFuser fuser = mock(ReciprocalRankFuser.class);
        when(fuser.fuse(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new CancellationException("fusion provider stop"));
        HybridRetriever retriever = hybridRetriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 1, 1);
        try {
            CancellationException cancelled = assertThrows(CancellationException.class,
                    () -> retriever.retrieveAll(List.of("one"), 1));
            assertEquals("fusion provider stop", cancelled.getMessage());
            assertEquals("provider_cancelled", TraceStore.get("hybrid.executor.terminalReason"));
        } finally {
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @Test
    void hybridFusionCallerInterruptionKeepsCallerCancellationReason() throws Exception {
        CountDownLatch fusionEntered = new CountDownLatch(1);
        CountDownLatch releaseFusion = new CountDownLatch(1);
        RetrievalHandler handler = (query, accumulator) ->
                accumulator.add(Content.from("real-evidence-before-caller-cancel"));
        ReciprocalRankFuser fuser = mock(ReciprocalRankFuser.class);
        when(fuser.fuse(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    fusionEntered.countDown();
                    try {
                        releaseFusion.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                });
        HybridRetriever retriever = hybridRetriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", false);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        ReflectionTestUtils.invokeMethod(retriever, "resetRetrievalExecutorForTest", 1, 1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Map<String, Object>> trace = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                retriever.retrieveAll(List.of("one"), 1);
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
                trace.set(TraceStore.getAll());
            }
        }, "hybrid-fusion-caller-cancel-test");
        caller.start();
        assertTrue(fusionEntered.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(1_000L);
        try {
            assertFalse(caller.isAlive());
            assertTrue(failure.get() instanceof CancellationException, String.valueOf(failure.get()));
            assertTrue(interrupted.get());
            assertEquals("caller_cancelled", trace.get().get("hybrid.executor.terminalReason"));
        } finally {
            releaseFusion.countDown();
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                    retriever, "shutdownRetrievalExecutorForTest", 1_000L));
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean awaitHybridIdle(HybridRetriever retriever, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            Map<String, Integer> metrics = ReflectionTestUtils.invokeMethod(
                    retriever, "retrievalExecutorMetricsForTest");
            if (metrics.get("active") == 0 && metrics.get("queued") == 0) return true;
            Thread.sleep(10L);
        }
        return false;
    }

    @Test
    void terminalPromptWebPolicyUsesDtoAndPlanMetadataAfterAllRetrievalStages() {
        DomainProfileLoader profiles = mock(DomainProfileLoader.class);
        when(profiles.isAllowedByProfile("https://evil.example/x", "official")).thenReturn(false);
        when(profiles.isAllowedByProfile("https://openai.com/docs", "official")).thenReturn(true);
        ChatWorkflow workflow = mock(ChatWorkflow.class, Answers.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "promptDomainProfiles", profiles);
        Content evil = Content.from(TextSegment.from(
                "private later-stage snippet",
                Metadata.from(Map.of("url", "https://evil.example/x"))));
        Content official = Content.from(TextSegment.from(
                "public official snippet",
                Metadata.from(Map.of("url", "https://openai.com/docs"))));
        ChatRequestDto dto = ChatRequestDto.builder()
                .message("latest update")
                .officialSourcesOnly(true)
                .build();

        @SuppressWarnings("unchecked")
        List<Content> filtered = ReflectionTestUtils.invokeMethod(
                workflow,
                "filterRequestScopedOfficialPromptWebDocs",
                dto,
                Map.of(),
                "latest update",
                List.of(evil, official),
                "pre_compression");

        assertEquals(List.of(official), filtered);
        assertEquals(2L, TraceStore.get("retrieval.integrity.inputCount"));
        assertEquals(1L, TraceStore.get("retrieval.integrity.policyDeniedCount"));
        assertEquals(1, TraceStore.get("retrieval.integrity.finalUsedCount"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private later-stage snippet"));

        @SuppressWarnings("unchecked")
        List<Content> postCompression = ReflectionTestUtils.invokeMethod(
                workflow,
                "filterRequestScopedOfficialPromptWebDocs",
                dto,
                Map.of(),
                "latest update",
                List.of(official),
                "post_compression");
        assertEquals(List.of(official), postCompression);
        assertEquals(3L, TraceStore.get("retrieval.integrity.inputCount"));
        assertEquals(1L, TraceStore.get("retrieval.integrity.policyDeniedCount"));
        assertEquals(1L, TraceStore.get("retrieval.integrity.filteredCount"));
        assertEquals(1, TraceStore.get("retrieval.integrity.finalUsedCount"));
        assertEquals(1, TraceStore.get(
                "retrieval.integrity.stage.pre_compression.policyDeniedCount"));
        assertEquals(0, TraceStore.get(
                "retrieval.integrity.stage.post_compression.policyDeniedCount"));

        TraceStore.clear();

        @SuppressWarnings("unchecked")
        List<Content> metadataFiltered = ReflectionTestUtils.invokeMethod(
                workflow,
                "filterRequestScopedOfficialPromptWebDocs",
                ChatRequestDto.builder().message("latest update").build(),
                Map.of("officialOnly", true, "domainProfile", "official"),
                "latest update",
                List.of(evil),
                "pre_compression");
        assertEquals(List.of(), metadataFiltered);
        assertEquals("hard_policy_filtered_empty",
                TraceStore.get("retrieval.integrity.emptyReason"));
    }

    @Test
    void hybridRelatednessFilterRestoresRejectedCandidatesOnlyWhenExplicitlyEnabled() {
        HybridRetriever retriever = hybridRetriever(
                mock(RetrievalHandler.class),
                mock(ReciprocalRankFuser.class));
        ReflectionTestUtils.setField(retriever, "minRelatedness", 0.75d);
        ReflectionTestUtils.setField(retriever, "topK", 4);
        ReflectionTestUtils.setField(retriever, "lightWeightRanker", null);
        ReflectionTestUtils.setField(retriever, "elementConstraintScorer", null);
        ReflectionTestUtils.setField(retriever, "relevanceScoringService",
                mock(com.example.lms.service.rag.RelevanceScoringService.class));
        Content lowQuality = Content.from("low-relatedness evidence");

        @SuppressWarnings("unchecked")
        List<Content> defaultFiltered = ReflectionTestUtils.invokeMethod(
                retriever,
                "finalizeResults",
                List.of(lowQuality),
                "text",
                List.of(),
                "query",
                Map.of());

        assertEquals(List.of(), defaultFiltered,
                "quality-rejected candidates must remain empty without an explicit fail-soft policy");
        assertEquals("relatedness_filtered_empty",
                TraceStore.get("retrieval.integrity.emptyReason"));
        assertEquals(1, TraceStore.get("retrieval.integrity.relatednessRejectedCount"));

        TraceStore.clear();
        ReflectionTestUtils.setField(retriever, "relatednessFilterFailSoft", true);
        @SuppressWarnings("unchecked")
        List<Content> explicitlyRestored = ReflectionTestUtils.invokeMethod(
                retriever,
                "finalizeResults",
                List.of(lowQuality),
                "text",
                List.of(),
                "query",
                Map.of());

        assertEquals(List.of(lowQuality), explicitlyRestored);
        assertEquals(true, TraceStore.get("retrieval.integrity.relatednessFailSoft"));
        assertEquals(1, TraceStore.get("retrieval.integrity.relatednessRestoredCount"));
    }

    private static AdaptiveWebSearchHandler adaptiveHandler(List<WebDocument> documents,
                                                            DomainProfileLoader profileLoader) {
        return new AdaptiveWebSearchHandler(
                new SearchDecisionService(),
                List.of(new FixedWebProvider(ProviderId.TAVILY, documents)),
                mock(PageContentScraper.class),
                mock(com.example.lms.service.rag.RelevanceScoringService.class),
                profileLoader);
    }

    private record FixedWebProvider(ProviderId id, List<WebDocument> documents)
            implements WebSearchProvider {
        @Override
        public WebSearchResult search(WebSearchQuery query) {
            return new WebSearchResult(id.name(), documents);
        }
    }

    @SuppressWarnings("unchecked")
    private static HybridRetriever hybridRetriever(RetrievalHandler handler,
                                                   ReciprocalRankFuser fuser) {
        return new HybridRetriever(
                mock(com.example.lms.service.rag.rerank.LightWeightRanker.class),
                mock(com.example.lms.service.rag.rerank.RerankGate.class),
                mock(com.example.lms.service.rag.auth.AuthorityScorer.class),
                handler,
                fuser,
                mock(com.example.lms.service.rag.AnswerQualityEvaluator.class),
                mock(com.example.lms.service.rag.SelfAskPlanner.class),
                mock(com.example.lms.service.rag.RelevanceScoringService.class),
                mock(com.example.lms.service.config.HyperparameterService.class),
                mock(com.example.lms.service.rag.rerank.ElementConstraintScorer.class),
                mock(com.example.lms.transform.QueryTransformer.class),
                mock(com.example.lms.service.scoring.AdaptiveScoringService.class),
                mock(com.example.lms.service.knowledge.KnowledgeBaseService.class),
                mock(com.example.lms.learning.NeuralPathFormationService.class),
                mock(com.example.lms.service.rag.SelfAskWebSearchRetriever.class),
                mock(com.example.lms.service.rag.AnalyzeWebSearchRetriever.class),
                mock(com.example.lms.service.rag.WebSearchRetriever.class),
                mock(com.example.lms.service.rag.QueryComplexityGate.class),
                mock(com.example.lms.service.rag.LangChainRAGService.class),
                mock(dev.langchain4j.model.embedding.EmbeddingModel.class),
                mock(EmbeddingStore.class),
                mock(com.example.lms.service.rag.detector.GameDomainDetector.class));
    }

    private static void assertRejection(HttpStatus status,
                                        String reason,
                                        org.junit.jupiter.api.function.Executable executable) {
        PublicRequestBudgetGuard.Rejection rejection =
                assertThrows(PublicRequestBudgetGuard.Rejection.class, executable);
        assertEquals(status, rejection.status());
        assertEquals(reason, rejection.reasonCode());
    }
}
