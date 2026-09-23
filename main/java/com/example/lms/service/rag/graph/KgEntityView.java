package com.example.lms.service.rag.graph;

public record KgEntityView(
        String name,
        String type,
        String domain,
        long mentionCount,
        double confidence) {
}
