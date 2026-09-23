package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceRepairHandlerTest {

    @Test
    void retrieveRunsWebOnceAndDedupesResults() {
        WebSearchRetriever web = mock(WebSearchRetriever.class);
        when(web.retrieve(any(Query.class))).thenReturn(List.of(
                Content.from("same evidence"),
                Content.from("same evidence"),
                Content.from("new evidence")
        ));
        EvidenceRepairHandler handler = new EvidenceRepairHandler(web, null, "", "");

        List<Content> out = handler.retrieve(new Query("rag critic retry"));

        assertEquals(2, out.size());
        verify(web).retrieve(any(Query.class));
    }
}
