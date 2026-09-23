
        package com.example.lms.api;

import com.example.lms.service.web.BraveSearchService;
import com.example.lms.service.web.MediaWikiClient;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;


import dev.langchain4j.model.chat.response.ChatResponse; // 변경됨
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Integration and health check endpoint.  This controller exposes a single
 * endpoint under <code>/api/integrations/check</code> that attempts to call
 * configured external services (Brave, MediaWiki, vector store and LLM) and
 * reports whether each integration is functioning.  Any exceptions are
 * captured and included in the response for diagnostic purposes.
 */
@RestController
@RequiredArgsConstructor
public class IntegrationController {
    private static final Logger log = LoggerFactory.getLogger(IntegrationController.class);

    private final BraveSearchService brave;
    private final MediaWikiClient wiki;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatModel chatModel;

    @GetMapping("/api/integrations/check")
    public ResponseEntity<Map<String, Object>> integrations() {
        Map<String, Object> r = new LinkedHashMap<>();
        // Test Brave search
        try {
            r.put("brave.sample", brave.searchSnippets("test", 1));
        } catch (Exception e) {
            traceSuppressed("integration.brave", e);
            r.put("brave.error", publicIntegrationError(e));
        }
        // Test MediaWiki search
        try {
            r.put("wiki.sample", wiki.searchExtracts("Korea", 1));
        } catch (Exception e) {
            traceSuppressed("integration.wiki", e);
            r.put("wiki.error", publicIntegrationError(e));
        }
        // Test vector store query (read-only friendly)
        try {
            var q = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                    .queryEmbedding(dev.langchain4j.data.embedding.Embedding.from(new float[]{0.1f, 0.2f}))
                    .maxResults(1)
                    .build();
            var res = embeddingStore.search(q);
            r.put("vector.query.ok", res != null);
        } catch (Exception e) {
            traceSuppressed("integration.vector", e);
            r.put("vector.error", publicIntegrationError(e));
        }
// Test LLM chat using ChatModel.chat(UserMessage) (LangChain4j 1.0.1)
        try {
            // --- 이 블록 전체가 변경됨 ---
            var resp = chatModel.chat(UserMessage.from("ping"));
            var ai = (resp != null) ? resp.aiMessage() : null;
            var text = (ai != null) ? ai.text() : null; // 1.0.1: AiMessage#text()
            boolean ok = (text != null && !text.isBlank());
            r.put("llm.ok", ok);
        } catch (Exception e) {
            traceSuppressed("integration.llm", e);
            r.put("llm.error", publicIntegrationError(e));
        }
        return ResponseEntity.ok(r);
    }

    static String publicIntegrationError(Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        return "errorCode=integration_check_failed"
                + " errorHash=" + com.example.lms.trace.SafeRedactor.hashValue(message)
                + " errorLength=" + message.length();
    }

    private static void traceSuppressed(String stage, Exception failure) {
        if (log.isDebugEnabled()) {
            log.debug("Integration diagnostics fallback stage={} errorType={}",
                    stage,
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
        }
    }
}
