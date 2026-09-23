package com.example.lms.service.rag.graph;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.MemorySnippet;
import com.example.lms.dto.learning.Triple;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class KnowledgeDeltaGraphProjector {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDeltaGraphProjector.class);

    private static final int MAX_TRIPLES = 80;
    private static final int MAX_MEMORIES = 80;
    private static final int MAX_TEXT_CHARS = 24_000;
    private static final int MAX_FIELD_CHARS = 280;
    private static final String SESSION_ID = "__KNOWLEDGE_DELTA__";
    private static final String SOURCE_TAG = "KNOWLEDGE_DELTA";

    private final GraphRagChunkingService chunkingService;

    public KnowledgeDeltaGraphProjector(GraphRagChunkingService chunkingService) {
        this.chunkingService = chunkingService;
    }

    public ProjectReport project(KnowledgeDelta delta) {
        if (delta == null || empty(delta)) {
            return new ProjectReport(false, 0, 0, "", "", "", "empty_delta", "");
        }
        if (chunkingService == null) {
            return new ProjectReport(false, 0, 0, "", "", "", "chunking_service_unavailable", "");
        }
        try {
            List<GraphRagChunkingService.IngestReport> reports = new ArrayList<>(2);
            List<KgChunk> tripleChunks = tripleChunks(delta);
            if (!tripleChunks.isEmpty()) {
                reports.add(chunkingService.ingestPreparedChunks(
                        SESSION_ID,
                        SOURCE_TAG,
                        tripleChunks,
                        BrainStateText.hash12(renderTripleSummary(delta.triples()))));
            }
            String memoryText = renderMemories(delta);
            if (StringUtils.hasText(memoryText)) {
                reports.add(chunkingService.ingestText(SESSION_ID, memoryText, SOURCE_TAG, inferDomain(delta)));
            }
            if (reports.isEmpty()) {
                return new ProjectReport(false, 0, 0, "", "", "", "empty_projection", "");
            }
            return ProjectReport.fromReports(reports);
        } catch (Exception ex) {
            traceSuppressed("project", ex);
            return new ProjectReport(false, 0, 0, "", "", "", "projection_failed", failureClass(ex));
        }
    }

    private static boolean empty(KnowledgeDelta delta) {
        return delta.triples().isEmpty() && delta.memories().isEmpty();
    }

    private static String renderMemories(KnowledgeDelta delta) {
        StringBuilder out = new StringBuilder(4096);
        out.append("### KNOWLEDGE_DELTA_GRAPH_PROJECTION\n");
        out.append("source=KnowledgeBaseService.apply\n");
        appendMemories(out, delta.memories());
        if (out.length() > MAX_TEXT_CHARS) {
            out.setLength(MAX_TEXT_CHARS);
            out.append("\n[KNOWLEDGE_DELTA_TRUNCATED]\n");
        }
        return out.toString();
    }

    private static List<KgChunk> tripleChunks(KnowledgeDelta delta) {
        if (delta == null || delta.triples() == null || delta.triples().isEmpty()) {
            return List.of();
        }
        List<KgChunk> out = new ArrayList<>();
        int count = 0;
        for (Triple triple : delta.triples()) {
            if (triple == null || count >= MAX_TRIPLES) {
                break;
            }
            String subject = safeField(triple.s());
            String predicate = safePredicate(triple.p());
            String object = safeField(triple.o());
            if (!StringUtils.hasText(subject) || !StringUtils.hasText(object)) {
                continue;
            }
            String domain = safeDomain(subject);
            if (domain.isBlank()) {
                domain = "GENERAL";
            }
            String sourceHash = SafeRedactor.hash12(triple.sourceUrl());
            String sourceText = renderTripleSourceText(subject, predicate, object, sourceHash);
            String chunkId = "knowledge-delta:" + DigestUtils.sha1Hex(subject + "|" + predicate + "|" + object + "|" + sourceHash);
            out.add(new KgChunk(
                    chunkId,
                    SESSION_ID,
                    sourceText,
                    uniqueEntities(subject, object, domain),
                    List.of(GraphRagPortMappingConnector.connect(
                            subject,
                            GraphRagPortMappingConnector.DEFAULT_SOURCE_PORT,
                            object,
                            GraphRagPortMappingConnector.DEFAULT_TARGET_PORT,
                            predicate,
                            predicate,
                            0.90d,
                            sourceHash)),
                    domain,
                    0.90d,
                    Instant.now()));
            count++;
        }
        return out;
    }

    private static List<KgChunk.KgEntity> uniqueEntities(String subject, String object, String domain) {
        Set<String> names = new LinkedHashSet<>();
        if (StringUtils.hasText(subject)) {
            names.add(subject);
        }
        if (StringUtils.hasText(object)) {
            names.add(object);
        }
        return names.stream()
                .map(name -> new KgChunk.KgEntity(name, "ENTITY", domain, 0.90d))
                .toList();
    }

    private static String renderTripleSourceText(String subject, String predicate, String object, String sourceHash) {
        StringBuilder out = new StringBuilder(256);
        out.append("### KNOWLEDGE_DELTA_TRIPLE\n");
        out.append("subject: ").append(subject).append('\n');
        out.append("predicate: ").append(predicate).append('\n');
        out.append("object: ").append(object).append('\n');
        out.append("relationText: ").append(subject).append(' ').append(predicate).append(' ').append(object).append('\n');
        if (StringUtils.hasText(sourceHash)) {
            out.append("sourceHash: ").append(sourceHash).append('\n');
        }
        return out.toString();
    }

    private static String renderTripleSummary(List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(1024);
        int count = 0;
        for (Triple triple : triples) {
            if (triple == null || count++ >= MAX_TRIPLES) {
                break;
            }
            out.append(safeField(triple.s())).append('|')
                    .append(safePredicate(triple.p())).append('|')
                    .append(safeField(triple.o())).append('|')
                    .append(SafeRedactor.hash12(triple.sourceUrl())).append('\n');
        }
        return out.toString();
    }

    private static void appendTriples(StringBuilder out, List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        out.append("triples:\n");
        int count = 0;
        for (Triple triple : triples) {
            if (triple == null || count++ >= MAX_TRIPLES) {
                break;
            }
            String subject = safeField(triple.s());
            String predicate = safePredicate(triple.p());
            String object = safeField(triple.o());
            if (!StringUtils.hasText(subject) || !StringUtils.hasText(object)) {
                continue;
            }
            out.append("- subject: ").append(subject).append('\n');
            out.append("  predicate: ").append(predicate).append('\n');
            out.append("  object: ").append(object).append('\n');
            out.append("  relationText: ").append(subject).append(' ')
                    .append(predicate).append(' ').append(object).append('\n');
            String sourceHash = SafeRedactor.hash12(triple.sourceUrl());
            if (StringUtils.hasText(sourceHash)) {
                out.append("  sourceHash: ").append(sourceHash).append('\n');
            }
        }
    }

    private static void appendMemories(StringBuilder out, List<MemorySnippet> memories) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        out.append("memories:\n");
        int count = 0;
        for (MemorySnippet memory : memories) {
            if (memory == null || count++ >= MAX_MEMORIES) {
                break;
            }
            String subject = safeField(memory.subject());
            String text = safeField(memory.text());
            if (!StringUtils.hasText(text)) {
                continue;
            }
            out.append("- subject: ").append(StringUtils.hasText(subject) ? subject : "GENERAL").append('\n');
            out.append("  confidence: ").append(String.format(Locale.ROOT, "%.2f", clamp01(memory.confidence()))).append('\n');
            out.append("  text: ").append(text).append('\n');
        }
    }

    private static String inferDomain(KnowledgeDelta delta) {
        List<String> candidates = new ArrayList<>();
        for (MemorySnippet memory : delta.memories()) {
            if (memory != null) {
                candidates.add(memory.subject());
            }
        }
        for (Triple triple : delta.triples()) {
            if (triple != null) {
                candidates.add(triple.s());
            }
        }
        for (String candidate : candidates) {
            String normalized = safeDomain(candidate);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "GENERAL";
    }

    private static String safeDomain(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-]", "_");
        normalized = normalized.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (normalized.length() < 2 || normalized.length() > 40) {
            return "";
        }
        return normalized;
    }

    private static String safePredicate(String value) {
        String v = safeField(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\- ]", " ");
        v = v.replaceAll("\\s+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        return v.isBlank() ? "RELATED_TO" : v;
    }

    private static String safeField(String value) {
        if (value == null) {
            return "";
        }
        String safe = SafeRedactor.redact(value);
        if (safe == null) {
            return "";
        }
        safe = safe.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (safe.length() > MAX_FIELD_CHARS) {
            return safe.substring(0, MAX_FIELD_CHARS);
        }
        return safe;
    }

    private static String safeReason(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }

    private static String failureClass(Throwable failure) {
        Throwable root = rootCause(failure);
        String className = root == null ? "" : root.getClass().getSimpleName();
        String lowerClass = className.toLowerCase(Locale.ROOT);
        String message = root == null || root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        if (root instanceof java.util.concurrent.CancellationException
                || root instanceof InterruptedException
                || lowerClass.contains("cancel")
                || lowerClass.contains("interrupt")
                || message.contains("cancelled")
                || message.contains("canceled")
                || message.contains("interrupted")) {
            return "cancelled";
        }
        return StringUtils.hasText(className) ? className : "Exception";
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && current.getCause() != current && depth++ < 8) {
            current = current.getCause();
        }
        return current;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeFailureClass = failureClass(failure);
        TraceStore.put("retrieval.kg.knowledgeDelta." + safeStage + ".suppressed", true);
        TraceStore.put("retrieval.kg.knowledgeDelta." + safeStage + ".failureClass", safeFailureClass);
        TraceStore.put("retrieval.kg.knowledgeDelta." + safeStage + ".fallback", "projection_failed");
        if (log.isDebugEnabled()) {
            log.debug("[KnowledgeDeltaGraphProjector] fail-soft stage={} err={}", safeStage, safeFailureClass);
        }
    }

    public record ProjectReport(
            boolean projected,
            int chunks,
            int neo4jWritten,
            String neo4jStatus,
            String neo4jDisabledReason,
            String vectorStatus,
            String disabledReason,
            String failureClass) {

        public ProjectReport(boolean projected, int chunks, String disabledReason, String failureClass) {
            this(projected, chunks, 0, "", "", "", disabledReason, failureClass);
        }

        static ProjectReport fromReports(List<GraphRagChunkingService.IngestReport> reports) {
            boolean projected = false;
            int chunks = 0;
            int neo4jWritten = 0;
            String neo4jStatus = "";
            String neo4jDisabledReason = "";
            String vectorStatus = "";
            String disabledReason = "";
            String failureClass = "";
            if (reports != null) {
                for (GraphRagChunkingService.IngestReport report : reports) {
                    if (report == null) {
                        continue;
                    }
                    chunks += Math.max(0, report.chunkCount());
                    neo4jWritten += Math.max(0, report.neo4jWriteCount());
                    projected = projected || "indexed".equalsIgnoreCase(report.status()) || report.neo4jWriteCount() > 0;
                    neo4jStatus = mergeStatus(neo4jStatus, backendString(report, "neo4jStatus"));
                    neo4jDisabledReason = firstNonBlank(neo4jDisabledReason, backendString(report, "neo4jDisabledReason"));
                    vectorStatus = mergeStatus(vectorStatus, backendString(report, "vectorStatus"));
                    disabledReason = firstNonBlank(disabledReason, safeReason(report.disabledReason()));
                    failureClass = firstNonBlank(failureClass, backendString(report, "failureClass"));
                    failureClass = firstNonBlank(failureClass, backendString(report, "neo4jFailureClass"));
                }
            }
            if (!projected && disabledReason.isBlank()) {
                disabledReason = "projection_not_persisted";
            }
            return new ProjectReport(projected, chunks, neo4jWritten, neo4jStatus, neo4jDisabledReason,
                    vectorStatus, disabledReason, failureClass);
        }

        private static String backendString(GraphRagChunkingService.IngestReport report, String key) {
            if (report == null || report.backend() == null || key == null) {
                return "";
            }
            Object value = report.backend().get(key);
            return value == null ? "" : safeReason(String.valueOf(value));
        }

        private static String mergeStatus(String current, String next) {
            if (!StringUtils.hasText(next)) {
                return current == null ? "" : current;
            }
            if (!StringUtils.hasText(current)) {
                return next;
            }
            return current.equals(next) ? current : "mixed";
        }

        private static String firstNonBlank(String current, String next) {
            return StringUtils.hasText(current) ? current : (StringUtils.hasText(next) ? next : "");
        }
    }
}
