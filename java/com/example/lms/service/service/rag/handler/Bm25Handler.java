package com.example.lms.service.service.rag.handler;

import com.example.lms.service.service.rag.bm25.Bm25Index;
import com.example.lms.service.service.rag.bm25.Bm25Retriever;
import java.util.*;

/** Chain handler stub for BM25 retrieval. */
public class Bm25Handler {
    private final Bm25Retriever retriever;
    private boolean enabled=true; private int topK=8;
    public Bm25Handler(Bm25Index index) { this.retriever=new Bm25Retriever(index); }
    public void setEnabled(boolean enabled) { this.enabled=enabled; }
    public void setTopK(int topK) { this.topK=topK; }
    public List<Bm25Retriever.Result> handle(String query) {
        if(!enabled) return Collections.emptyList();
        return retriever.retrieve(query, topK);
    }
}