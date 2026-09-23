package com.example.lms.service.rag.graph;

public record PortMappingView(
        String connectorHash12,
        String sourceNodeHash12,
        String sourcePort,
        String targetNodeHash12,
        String targetPort,
        String relationKind,
        String connectorKind,
        long count) {
}
