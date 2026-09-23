package com.example.lms.service.rag.graph;

public record DomainSummary(
        String domain,
        long chunkCount,
        long entityCount,
        long relationCount) {
}
