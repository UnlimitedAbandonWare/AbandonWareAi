package com.example.lms.service.rag.graph;

import java.time.Instant;
import java.util.List;

public record KgChunk(
        String chunkId,
        String sessionId,
        String sourceText,
        List<KgEntity> entities,
        List<KgRelation> relations,
        String domain,
        double confidence,
        Instant createdAt,
        String sourceTag,
        String docType,
        String origin,
        String ingestLane) {

    public KgChunk(String chunkId,
                   String sessionId,
                   String sourceText,
                   List<KgEntity> entities,
                   List<KgRelation> relations,
                   String domain,
                   double confidence,
                   Instant createdAt) {
        this(chunkId, sessionId, sourceText, entities, relations, domain, confidence, createdAt,
                "", "", "", "");
    }

    public KgChunk {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        sourceTag = safeMeta(sourceTag);
        docType = safeMeta(docType);
        origin = safeMeta(origin);
        ingestLane = safeMeta(ingestLane);
    }

    private static String safeMeta(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        return safe.length() <= 120 ? safe : safe.substring(0, 120);
    }

    public record KgEntity(String name, String type, String domain, double confidence) {
    }

    public record KgRelation(
            String source,
            String target,
            String kind,
            double confidence,
            String sourcePort,
            String targetPort,
            String connectorKind,
            String connectorHash12) {

        public KgRelation(String source, String target, String kind, double confidence) {
            this(source, target, kind, confidence,
                    GraphRagPortMappingConnector.DEFAULT_SOURCE_PORT,
                    GraphRagPortMappingConnector.DEFAULT_TARGET_PORT,
                    kind,
                    "");
        }

        public KgRelation {
            kind = GraphRagPortMappingConnector.safeKind(kind, "RELATED_TO");
            sourcePort = GraphRagPortMappingConnector.safePort(
                    sourcePort,
                    GraphRagPortMappingConnector.DEFAULT_SOURCE_PORT);
            targetPort = GraphRagPortMappingConnector.safePort(
                    targetPort,
                    GraphRagPortMappingConnector.DEFAULT_TARGET_PORT);
            connectorKind = GraphRagPortMappingConnector.safeKind(connectorKind, kind);
            connectorHash12 = GraphRagPortMappingConnector.safeHash12(
                    connectorHash12,
                    source,
                    sourcePort,
                    target,
                    targetPort,
                    connectorKind);
        }
    }
}
