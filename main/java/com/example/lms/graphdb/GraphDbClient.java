package com.example.lms.graphdb;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.graph.Neo4jKgChunkWriter;
import com.example.lms.trace.SafeRedactor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class GraphDbClient {

    private final Neo4jKgChunkWriter writer;

    public GraphDbClient(Neo4jKgChunkWriter writer) {
        this.writer = writer;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        putManualBoundary(out);
        if (writer == null) {
            out.put("enabled", false);
            out.put("disabledReason", "writer_unavailable");
            return Map.copyOf(out);
        }
        putWriterStatus(out, writer.status());
        putManualBoundary(out);
        return Map.copyOf(out);
    }

    public Map<String, Object> manualEvidence(String domain, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", "neo4j");
        out.put("writeBoundary", "graphdb_manual_learning");
        out.put("readBoundary", "graphdb_manual_learning");
        out.put("rawTextIncluded", false);
        out.put("rawEntityValuesIncluded", false);
        out.put("rawIdentifiersIncluded", false);
        out.put("rawSecretsIncluded", false);
        if (writer == null) {
            out.put("enabled", false);
            out.put("status", "disabled");
            out.put("disabledReason", "writer_unavailable");
            out.put("returnedCount", 0);
            out.put("candidates", List.of());
            return Map.copyOf(out);
        }
        Neo4jKgChunkWriter.ManualEvidenceReport report = writer.readManualEvidence(domain, limit);
        out.put("enabled", report.enabled());
        out.put("status", safeCode(report.status()));
        out.put("disabledReason", safeCode(report.disabledReason()));
        out.put("endpointHost", endpointHostOnly(report.endpointHost()));
        out.put("returnedCount", Math.max(0, report.returnedCount()));
        out.put("failureClass", safeFailureClass(report.failureClass()));
        out.put("candidates", report.candidates().stream()
                .map(GraphDbClient::candidate)
                .toList());
        return Map.copyOf(out);
    }

    private static Map<String, Object> candidate(Neo4jKgChunkWriter.ManualEvidenceCandidate candidate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunkHash", hash12(candidate.chunkId()));
        out.put("sessionHash", hashToken(candidate.sessionHash()));
        out.put("textHash", hashToken(candidate.textHash()));
        out.put("textLength", candidate.textLength());
        out.put("domain", safeCode(candidate.domain()));
        out.put("confidence", candidate.confidence());
        out.put("sourceTag", safeCode(candidate.sourceTag()));
        out.put("docType", safeCode(candidate.docType()));
        out.put("origin", safeCode(candidate.origin()));
        out.put("ingestLane", safeCode(candidate.ingestLane()));
        out.put("entityCount", candidate.entityHashes().size());
        out.put("entityHashes", candidate.entityHashes().stream()
                .map(GraphDbClient::hashToken)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList());
        out.put("hops", candidate.hops().stream().map(GraphDbClient::hop).toList());
        return Map.copyOf(out);
    }

    private static Map<String, Object> hop(Neo4jKgChunkWriter.ManualHop hop) {
        return Map.of(
                "targetHash", hashToken(hop.targetHash()),
                "kind", safeCode(hop.kind()),
                "pathHash", hashToken(hop.pathHash()),
                "connectorHash", hashToken(hop.connectorHash()),
                "relationSource", safeCode(hop.relationSource()));
    }

    private static void putManualBoundary(Map<String, Object> out) {
        out.put("backend", "neo4j");
        out.put("writeBoundary", "graphdb_manual_learning");
        out.put("readBoundary", "graphdb_manual_learning");
        out.put("rawTextIncluded", false);
        out.put("rawEntityValuesIncluded", false);
        out.put("rawIdentifiersIncluded", false);
        out.put("rawSecretsIncluded", false);
    }

    private static void putWriterStatus(Map<String, Object> out, Map<String, Object> writerStatus) {
        if (writerStatus == null) {
            out.put("enabled", false);
            out.put("disabledReason", "writer_status_unavailable");
            return;
        }
        copy(out, writerStatus, "enabled");
        copyEndpointHost(out, writerStatus);
        copyCode(out, writerStatus, "disabledReason");
        copyCode(out, writerStatus, "lastWriteStatus");
        copyFailureClass(out, writerStatus, "lastWriteFailureClass");
        copyInteger(out, writerStatus, "lastWrittenChunks");
        copyInteger(out, writerStatus, "lastWrittenEntities");
        copyInteger(out, writerStatus, "lastWrittenRelations");
        copyInteger(out, writerStatus, "lastWrittenPortMappings");
    }

    private static void copy(Map<String, Object> out, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            out.put(key, source.get(key));
        }
    }

    private static void copyEndpointHost(Map<String, Object> out, Map<String, Object> source) {
        if (source.containsKey("endpointHost")) {
            out.put("endpointHost", endpointHostOnly(source.get("endpointHost")));
        }
    }

    private static void copyCode(Map<String, Object> out, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            out.put(key, safeCode(source.get(key)));
        }
    }

    private static void copyFailureClass(Map<String, Object> out, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            out.put(key, safeFailureClass(source.get(key)));
        }
    }

    private static void copyInteger(Map<String, Object> out, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            out.put(key, number.intValue());
        }
    }

    private static String endpointHostOnly(Object value) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (raw.isBlank()) {
            return "";
        }
        try {
            if (raw.contains("://")) {
                URI parsed = URI.create(raw);
                String host = parsed.getHost();
                return host == null ? "" : safeHost(host);
            }
        } catch (Exception ignore) {
            traceSuppressed("graphDb.client.endpointHost", ignore);
            return "";
        }
        int at = raw.lastIndexOf('@');
        String host = at >= 0 ? raw.substring(at + 1) : raw;
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int question = host.indexOf('?');
        if (question >= 0) {
            host = host.substring(0, question);
        }
        return safeHost(host);
    }

    private static String safeHost(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (!trimmed.matches("[A-Za-z0-9.:-]{1,120}")) {
            return "";
        }
        return trimmed;
    }

    private static String safeCode(Object value) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("secret")
                || lower.contains("token")
                || lower.contains("authorization")
                || lower.contains("bearer")
                || lower.startsWith("sk-")) {
            return "redacted";
        }
        if (!raw.matches("[A-Za-z0-9_.:-]{1,120}")) {
            return "redacted";
        }
        return raw;
    }

    private static String safeFailureClass(Object value) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("cancel") || lower.contains("interrupt")) {
            return "cancelled";
        }
        return safeCode(raw);
    }

    private static String hashToken(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase();
        if (lower.matches("[a-f0-9]{12,64}")) {
            return lower;
        }
        return hash12(raw);
    }

    private static String hash12(String value) {
        String safe = value == null ? "" : value;
        return DigestUtils.sha256Hex(safe).substring(0, 12);
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(safeStage, ignored);
        TraceStore.put("graphdb.client.suppressed.stage", safeStage);
        TraceStore.put("graphdb.client.suppressed.errorType", safeErrorType);
        TraceStore.put("graphdb.client.suppressed." + safeStage, true);
        TraceStore.put("graphdb.client.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(String stage, Throwable ignored) {
        if ("graphDb.client.endpointHost".equals(stage) && ignored instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return ignored == null ? "unknown" : SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown");
    }
}
