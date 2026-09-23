package com.example.lms.uaw.thumbnail;

import com.example.lms.llm.ChatModel;
import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UawThumbnailServiceGraphRagEventTest {

    @Test
    void generateAndPersistPublishesBoundedGraphRagEventAfterKnowledgeWrite() {
        UawThumbnailProperties props = new UawThumbnailProperties();
        props.setEnabled(true);
        props.setMinConfidence(0.5d);
        UawThumbnailPlanLoader planLoader = mock(UawThumbnailPlanLoader.class);
        when(planLoader.loadOrDefault(anyString(), org.mockito.ArgumentMatchers.eq(props)))
                .thenReturn(new UawThumbnailPlanSpec(
                        "UAW_thumbnail.v1",
                        1,
                        "uaw_thumbnail",
                        new UawThumbnailPlanSpec.Anchors(3, "fast", 128, 0.0d),
                        new UawThumbnailPlanSpec.Evidence(2, 1, 2, false),
                        new UawThumbnailPlanSpec.Render("STRICT", "mini", 256, 0.2d),
                        new UawThumbnailPlanSpec.Persist("UAW_THUMB", "THUMBNAIL", 0.5d)));

        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        when(webSearchProvider.search(anyString(), anyInt())).thenReturn(List.of(
                "Alpha source\nGrounded Alpha quote\nhttps://example.com/alpha",
                "Beta source\nGrounded Beta quote\nhttps://example.org/beta"));
        QueryKeywordPromptBuilder keywordPromptBuilder = mock(QueryKeywordPromptBuilder.class);
        when(keywordPromptBuilder.buildKeywordVariantsPrompt(anyString(), anyString(), anyInt()))
                .thenReturn("anchor prompt");
        when(keywordPromptBuilder.buildUawThumbnailRenderPrompt(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> java.util.Arrays.stream(invocation.getArguments())
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining("\n")));
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.generate(anyString(), anyDouble(), anyInt()))
                .thenReturn("Alpha\nBeta\ncoastal route")
                .thenReturn("""
                        {
                          "attributes": {
                            "caption": "Alpha and Beta are related through a coastal route.",
                            "anchors": ["Alpha", "Beta"],
                            "facts": [{"claim": "Alpha relates to Beta.", "cite": [0]}]
                          },
                          "sources": [],
                          "confidenceScore": 0.93
                        }
                        """);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        when(knowledgeBase.find(anyString(), anyString())).thenReturn(Optional.empty());
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        UawThumbnailService service = new UawThumbnailService(
                props,
                planLoader,
                webSearchProvider,
                keywordPromptBuilder,
                chatModel,
                knowledgeBase,
                eventPublisher);

        Optional<UawThumbnailService.ThumbnailResult> result = service.generateAndPersist(
                "raw sensitive topic that should not be copied into the event");

        assertTrue(result.isPresent());
        verify(knowledgeBase).integrateVerifiedKnowledge(
                org.mockito.ArgumentMatchers.eq("UAW_THUMB"),
                org.mockito.ArgumentMatchers.startsWith("UAW_THUMB::"),
                anyString(),
                anyList(),
                org.mockito.ArgumentMatchers.eq(0.93d));
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        UawThumbnailPersistedEvent event = assertInstanceOf(
                UawThumbnailPersistedEvent.class,
                eventCaptor.getValue());
        assertTrue(event.graphText().contains("Alpha and Beta"));
        assertTrue(event.anchors().contains("coastal route"));
        assertFalse(event.toString().contains("raw sensitive topic"));
        assertFalse(event.graphText().contains("https://example.com"));
    }

    @Test
    void generateAndPersistMasksSecretLikeTopicBeforeOutboundPromptsAndSearch() {
        String rawKey = "sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        String rawOwnerToken = "raw-owner-token";
        String supabaseSecret = "sb_secret_" + "thumbnailtopic123456";
        String rawTopic = "thumbnail bridge " + rawKey + " ownerToken=" + rawOwnerToken + " " + supabaseSecret;

        UawThumbnailProperties props = new UawThumbnailProperties();
        props.setEnabled(true);
        props.setMinConfidence(0.5d);
        UawThumbnailPlanLoader planLoader = mock(UawThumbnailPlanLoader.class);
        when(planLoader.loadOrDefault(anyString(), org.mockito.ArgumentMatchers.eq(props)))
                .thenReturn(new UawThumbnailPlanSpec(
                        "UAW_thumbnail.v1",
                        1,
                        "uaw_thumbnail",
                        new UawThumbnailPlanSpec.Anchors(3, "fast", 128, 0.0d),
                        new UawThumbnailPlanSpec.Evidence(2, 1, 2, false),
                        new UawThumbnailPlanSpec.Render("STRICT", "mini", 256, 0.2d),
                        new UawThumbnailPlanSpec.Persist("UAW_THUMB", "THUMBNAIL", 0.5d)));

        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        when(webSearchProvider.search(anyString(), anyInt())).thenReturn(List.of(
                "Alpha source\nGrounded Alpha quote\nhttps://example.com/alpha",
                "Beta source\nGrounded Beta quote\nhttps://example.org/beta"));
        QueryKeywordPromptBuilder keywordPromptBuilder = mock(QueryKeywordPromptBuilder.class);
        when(keywordPromptBuilder.buildKeywordVariantsPrompt(anyString(), anyString(), anyInt()))
                .thenReturn("anchor prompt");
        when(keywordPromptBuilder.buildUawThumbnailRenderPrompt(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> java.util.Arrays.stream(invocation.getArguments())
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining("\n")));
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.generate(anyString(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("anchor generator unavailable"))
                .thenReturn("""
                        {
                          "attributes": {
                            "caption": "Alpha and Beta are related.",
                            "anchors": ["Alpha", "Beta"],
                            "facts": [{"claim": "Alpha relates to Beta.", "cite": [0]}]
                          },
                          "sources": [],
                          "confidenceScore": 0.91
                        }
                        """);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        when(knowledgeBase.find(anyString(), anyString())).thenReturn(Optional.empty());

        UawThumbnailService service = new UawThumbnailService(
                props,
                planLoader,
                webSearchProvider,
                keywordPromptBuilder,
                chatModel,
                knowledgeBase,
                mock(ApplicationEventPublisher.class));

        Optional<UawThumbnailService.ThumbnailResult> result = service.generateAndPersist(rawTopic);

        assertTrue(result.isPresent());
        assertFalse(result.get().topic().contains(rawKey));
        assertFalse(result.get().topic().contains(rawOwnerToken));
        assertFalse(result.get().topic().contains(supabaseSecret));
        assertFalse(result.get().topic().contains("sb_secret_"));
        assertFalse(result.get().entityName().contains(rawKey));
        assertFalse(result.get().entityName().contains(rawOwnerToken));
        assertFalse(result.get().entityName().contains(supabaseSecret));
        assertFalse(result.get().entityName().contains("sb_secret_"));

        ArgumentCaptor<String> keywordCleaned = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keywordSubject = ArgumentCaptor.forClass(String.class);
        verify(keywordPromptBuilder).buildKeywordVariantsPrompt(keywordCleaned.capture(), keywordSubject.capture(), anyInt());
        assertFalse(keywordCleaned.getValue().contains(rawKey));
        assertFalse(keywordCleaned.getValue().contains(rawOwnerToken));
        assertFalse(keywordCleaned.getValue().contains(supabaseSecret));
        assertFalse(keywordCleaned.getValue().contains("sb_secret_"));
        assertFalse(keywordSubject.getValue().contains(rawKey));
        assertFalse(keywordSubject.getValue().contains(rawOwnerToken));
        assertFalse(keywordSubject.getValue().contains(supabaseSecret));
        assertFalse(keywordSubject.getValue().contains("sb_secret_"));

        ArgumentCaptor<String> webQuery = ArgumentCaptor.forClass(String.class);
        verify(webSearchProvider, org.mockito.Mockito.atLeastOnce()).search(webQuery.capture(), anyInt());
        assertFalse(String.join("\n", webQuery.getAllValues()).contains(rawKey));
        assertFalse(String.join("\n", webQuery.getAllValues()).contains(rawOwnerToken));
        assertFalse(String.join("\n", webQuery.getAllValues()).contains(supabaseSecret));
        assertFalse(String.join("\n", webQuery.getAllValues()).contains("sb_secret_"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel, org.mockito.Mockito.times(2)).generate(prompt.capture(), anyDouble(), anyInt());
        assertFalse(String.join("\n", prompt.getAllValues()).contains(rawKey));
        assertFalse(String.join("\n", prompt.getAllValues()).contains(rawOwnerToken));
        assertFalse(String.join("\n", prompt.getAllValues()).contains(supabaseSecret));
        assertFalse(String.join("\n", prompt.getAllValues()).contains("sb_secret_"));

        ArgumentCaptor<String> entityName = ArgumentCaptor.forClass(String.class);
        verify(knowledgeBase).find(org.mockito.ArgumentMatchers.eq("UAW_THUMB"), entityName.capture());
        verify(knowledgeBase).integrateVerifiedKnowledge(
                org.mockito.ArgumentMatchers.eq("UAW_THUMB"),
                entityName.capture(),
                anyString(),
                anyList(),
                org.mockito.ArgumentMatchers.eq(0.91d));
        assertFalse(String.join("\n", entityName.getAllValues()).contains(rawKey));
        assertFalse(String.join("\n", entityName.getAllValues()).contains(rawOwnerToken));
        assertFalse(String.join("\n", entityName.getAllValues()).contains(supabaseSecret));
        assertFalse(String.join("\n", entityName.getAllValues()).contains("sb_secret_"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateAndPersistSanitizesEvidenceBeforeRenderPromptAndKnowledgeSources() {
        String rawKey = "sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        String rawOwnerToken = "raw-owner-token";
        String supabaseSecret = "sb_secret_" + "thumbnailevidence123456";

        UawThumbnailProperties props = new UawThumbnailProperties();
        props.setEnabled(true);
        props.setMinConfidence(0.5d);
        UawThumbnailPlanLoader planLoader = mock(UawThumbnailPlanLoader.class);
        when(planLoader.loadOrDefault(anyString(), org.mockito.ArgumentMatchers.eq(props)))
                .thenReturn(new UawThumbnailPlanSpec(
                        "UAW_thumbnail.v1",
                        1,
                        "uaw_thumbnail",
                        new UawThumbnailPlanSpec.Anchors(3, "fast", 128, 0.0d),
                        new UawThumbnailPlanSpec.Evidence(2, 1, 2, false),
                        new UawThumbnailPlanSpec.Render("STRICT", "mini", 256, 0.2d),
                        new UawThumbnailPlanSpec.Persist("UAW_THUMB", "THUMBNAIL", 0.5d)));

        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        when(webSearchProvider.search(anyString(), anyInt())).thenReturn(List.of(
                "Alpha source\nGrounded Alpha quote " + rawKey + " " + supabaseSecret + "\nhttps://example.com/alpha?ownerToken=" + rawOwnerToken,
                "Beta source\nGrounded Beta quote\nhttps://example.org/beta?api_key=" + rawKey + "&sb=" + supabaseSecret + "#frag"));
        QueryKeywordPromptBuilder keywordPromptBuilder = mock(QueryKeywordPromptBuilder.class);
        when(keywordPromptBuilder.buildKeywordVariantsPrompt(anyString(), anyString(), anyInt()))
                .thenReturn("anchor prompt");
        when(keywordPromptBuilder.buildUawThumbnailRenderPrompt(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> java.util.Arrays.stream(invocation.getArguments())
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining("\n")));
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.generate(anyString(), anyDouble(), anyInt()))
                .thenReturn("Alpha\nBeta")
                .thenReturn("""
                        {
                          "attributes": {
                            "caption": "Alpha and Beta are related.",
                            "anchors": ["Alpha", "Beta"],
                            "facts": [{"claim": "Alpha relates to Beta.", "cite": [0]}]
                          },
                          "sources": [],
                          "confidenceScore": 0.92
                        }
                        """);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        when(knowledgeBase.find(anyString(), anyString())).thenReturn(Optional.empty());

        UawThumbnailService service = new UawThumbnailService(
                props,
                planLoader,
                webSearchProvider,
                keywordPromptBuilder,
                chatModel,
                knowledgeBase,
                mock(ApplicationEventPublisher.class));

        Optional<UawThumbnailService.ThumbnailResult> result = service.generateAndPersist("public thumbnail topic");

        assertTrue(result.isPresent());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel, org.mockito.Mockito.times(2)).generate(prompt.capture(), anyDouble(), anyInt());
        String promptDump = String.join("\n", prompt.getAllValues());
        assertFalse(promptDump.contains(rawKey));
        assertFalse(promptDump.contains(rawOwnerToken));
        assertFalse(promptDump.contains(supabaseSecret));
        assertFalse(promptDump.contains("sb_secret_"));
        assertFalse(promptDump.contains("ownerToken"));
        assertFalse(promptDump.contains("api_key"));

        ArgumentCaptor<String> knowledgeJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> sources = ArgumentCaptor.forClass((Class) List.class);
        verify(knowledgeBase).integrateVerifiedKnowledge(
                org.mockito.ArgumentMatchers.eq("UAW_THUMB"),
                org.mockito.ArgumentMatchers.startsWith("UAW_THUMB::"),
                knowledgeJson.capture(),
                sources.capture(),
                org.mockito.ArgumentMatchers.eq(0.92d));
        String persistedDump = knowledgeJson.getValue() + "\n" + String.join("\n", sources.getValue());
        assertFalse(persistedDump.contains(rawKey));
        assertFalse(persistedDump.contains(rawOwnerToken));
        assertFalse(persistedDump.contains(supabaseSecret));
        assertFalse(persistedDump.contains("sb_secret_"));
        assertFalse(persistedDump.contains("ownerToken"));
        assertFalse(persistedDump.contains("api_key"));
        assertTrue(sources.getValue().contains("https://example.com/alpha"));
        assertTrue(sources.getValue().contains("https://example.org/beta"));
    }
}
