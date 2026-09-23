package com.example.lms.service.rag.retriever;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalBm25RetrieverTest {

    @Test
    void commonQueryTermStillReturnsMatchingDocuments() {
        LocalBm25Retriever retriever = new LocalBm25Retriever();
        retriever.add(new LocalBm25Retriever.Doc("doc-a", "rag evidence alpha"));
        retriever.add(new LocalBm25Retriever.Doc("doc-b", "rag evidence beta"));

        List<LocalBm25Retriever.Doc> results = retriever.topK("rag evidence", 5);

        assertEquals(2, results.size());
        assertEquals(List.of("doc-a", "doc-b"), results.stream().map(doc -> doc.id).sorted().toList());
    }
}
