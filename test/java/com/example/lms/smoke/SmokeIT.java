package com.example.lms.smoke;

import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic smoke tests exercising key integration points in the RAG pipeline.  These
 * tests verify that multiple web providers can be fanned out and cached,
 * that the Groq mini model is reachable for light NLP tasks, and that the
 * embedding store routes search queries through Upstash (read) and Pinecone
 * (write) as configured.  They intentionally avoid deep semantic assertions
 * and instead focus on ensuring that components are wired and functioning.
 */
@SpringBootTest
@ActiveProfiles({"ultra", "secrets"})
public class SmokeIT {

    @Autowired
    private WebSearchRetriever webSearchRetriever;

    @Autowired
    @Qualifier("miniModel")
    private ChatModel miniModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Test
    void web_three_way_fallback() {
        Query q = new Query("원신 스커크 최신 공략");
        List<Content> hits = webSearchRetriever.retrieve(q);
        assertNotNull(hits, "web search result should not be null");
        assertTrue(hits.size() > 0, "expected at least one web search hit");
    }

    @Test
    void llm_groq_roundtrip() {
        // Use the mini model to perform a simple rewriting task.  The
        // prompt asks the model to correct the spelling of the Genshin
        // character "Skirk" written as "스커크".  The response should
        // include the corrected name.
        var response = miniModel.chat(List.of(UserMessage.from("스커크 -> Skirk 철자 바로잡아")));
        assertNotNull(response, "mini model response should not be null");
        String out = response.aiMessage().text();
        assertNotNull(out, "mini model output text should not be null");
        assertTrue(out.contains("Skirk"), "rewritten text should contain 'Skirk'");
    }

    @Test
    void vector_query_upstash() {
        // Build a dummy embedding with two dimensions.  The Upstash vector
        // store supports arbitrary dimensions and will return an empty
        // response when no results are found.  The search should still
        // return a non-null result object via the composite store.
        var emb = dev.langchain4j.data.embedding.Embedding.from(new float[]{0.1f, 0.2f});
        EmbeddingSearchRequest request = new EmbeddingSearchRequest(emb, 3, 0.25);
        var resp = embeddingStore.search(request);
        assertNotNull(resp, "embedding store search response should not be null");
    }
}