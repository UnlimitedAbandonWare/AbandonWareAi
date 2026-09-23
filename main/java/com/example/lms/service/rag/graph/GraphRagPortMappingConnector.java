package com.example.lms.service.rag.graph;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class GraphRagPortMappingConnector {

    static final String DEFAULT_SOURCE_PORT = "semantic_out";
    static final String DEFAULT_TARGET_PORT = "semantic_in";

    private GraphRagPortMappingConnector() {
    }

    static KgChunk.KgRelation semanticRelation(
            String sourceNode,
            String targetNode,
            String relationKind,
            double confidence,
            String fingerprint) {
        return connect(
                sourceNode,
                DEFAULT_SOURCE_PORT,
                targetNode,
                DEFAULT_TARGET_PORT,
                relationKind,
                relationKind,
                confidence,
                fingerprint);
    }

    static KgChunk.KgRelation connect(
            String sourceNode,
            String sourcePort,
            String targetNode,
            String targetPort,
            String relationKind,
            String connectorKind,
            double confidence,
            String fingerprint) {
        String safeRelationKind = safeKind(relationKind, "RELATED_TO");
        String safeConnectorKind = safeKind(connectorKind, safeRelationKind);
        String safeSourcePort = safePort(sourcePort, DEFAULT_SOURCE_PORT);
        String safeTargetPort = safePort(targetPort, DEFAULT_TARGET_PORT);
        return new KgChunk.KgRelation(
                sourceNode,
                targetNode,
                safeRelationKind,
                confidence,
                safeSourcePort,
                safeTargetPort,
                safeConnectorKind,
                connectorHash12(sourceNode, safeSourcePort, targetNode, safeTargetPort, safeConnectorKind, fingerprint));
    }

    static Map<String, Object> publicSummary(KgChunk.KgRelation relation) {
        if (relation == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceNodeHash12", BrainStateText.hash12(relation.source()));
        out.put("sourcePort", safePort(relation.sourcePort(), DEFAULT_SOURCE_PORT));
        out.put("targetNodeHash12", BrainStateText.hash12(relation.target()));
        out.put("targetPort", safePort(relation.targetPort(), DEFAULT_TARGET_PORT));
        out.put("relationKind", safeKind(relation.kind(), "RELATED_TO"));
        out.put("connectorKind", safeKind(relation.connectorKind(), relation.kind()));
        out.put("connectorHash12", safeHash12(
                relation.connectorHash12(),
                relation.source(),
                relation.sourcePort(),
                relation.target(),
                relation.targetPort(),
                relation.connectorKind()));
        return Map.copyOf(out);
    }

    static String connectorHash12(
            String sourceNode,
            String sourcePort,
            String targetNode,
            String targetPort,
            String connectorKind,
            String fingerprint) {
        String material = String.join("|",
                String.valueOf(sourceNode),
                safePort(sourcePort, DEFAULT_SOURCE_PORT),
                String.valueOf(targetNode),
                safePort(targetPort, DEFAULT_TARGET_PORT),
                safeKind(connectorKind, "RELATED_TO"),
                String.valueOf(fingerprint));
        return BrainStateText.hash12(material);
    }

    static String safePort(String value, String fallback) {
        String v = StringUtils.hasText(value) ? value.trim() : fallback;
        v = v.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:\\-]+", "_");
        v = v.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (!StringUtils.hasText(v)) {
            v = StringUtils.hasText(fallback) ? fallback : DEFAULT_TARGET_PORT;
        }
        return v.length() <= 48 ? v : v.substring(0, 48);
    }

    static String safeKind(String value, String fallback) {
        String v = StringUtils.hasText(value) ? value.trim() : fallback;
        v = v.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-]+", "_");
        v = v.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (!StringUtils.hasText(v)) {
            v = StringUtils.hasText(fallback) ? fallback : "RELATED_TO";
        }
        return v.length() <= 80 ? v : v.substring(0, 80);
    }

    static String safeHash12(String value, Object... fallbackParts) {
        if (StringUtils.hasText(value)) {
            String trimmed = value.trim().replaceAll("[^A-Fa-f0-9]", "");
            if (trimmed.length() >= 12) {
                return trimmed.substring(0, 12).toLowerCase(Locale.ROOT);
            }
        }
        StringBuilder material = new StringBuilder(128);
        if (fallbackParts != null) {
            for (Object part : fallbackParts) {
                if (material.length() > 0) {
                    material.append('|');
                }
                material.append(String.valueOf(part));
            }
        }
        return BrainStateText.hash12(material.toString());
    }
}
