package com.example.lms.service.rag.security;

import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import java.util.Map;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that queries can be constructed with metadata containing the
 * session identifier (META_SID).  While this test does not exercise
 * the entire retrieval pipeline, it ensures that the builder API is
 * used to set metadata and that the resulting query exposes the
 * session id via its metadata accessor.
 */
public class QuerySidPropagationTest {

    @Test
    public void builderAttachesSessionMetadata() {
        String sid = "sessionXYZ";
        Query q = Query.builder()
                .text("Who am I?")
                .metadata(Metadata.from(Map.of(LangChainRAGService.META_SID, sid)))
                .build();
        // Ensure metadata is present and contains our session id
        Metadata md = q.metadata();
        assertNotNull(md, "Metadata should not be null");
        assertEquals(sid, md.asMap().get(LangChainRAGService.META_SID), "Session id must be attached in query metadata");
    }
}