package com.example.lms.service.rag.graph;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphProperties;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

@Service
public class Neo4jKgChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(Neo4jKgChunkWriter.class);

    private final Neo4jKnowledgeGraphProperties properties;
    private final BrainStateProperties brainProperties;
    private volatile Driver driver;
    private volatile WriteReport lastWrite = WriteReport.disabled("not_attempted");

    public Neo4jKgChunkWriter(Neo4jKnowledgeGraphProperties properties, BrainStateProperties brainProperties) {
        this.properties = properties;
        this.brainProperties = brainProperties;
    }

    public WriteReport writeChunks(List<KgChunk> chunks) {
        String disabled = disabledReason();
        if (disabled != null) {
            lastWrite = WriteReport.disabled(disabled);
            return lastWrite;
        }
        if (chunks == null || chunks.isEmpty()) {
            lastWrite = new WriteReport(true, "skipped", null, 0, 0, 0, endpointHost(), null);
            return lastWrite;
        }

        int batchSize = Math.max(1, brainProperties.getNeo4j().getIngestBatchSize());
        int chunkCount = 0;
        int entityCount = 0;
        int relationCount = 0;
        int portMappingCount = 0;
        String logLane = laneForLog(chunks);
        try (Session session = openSession()) {
            for (int start = 0; start < chunks.size(); start += batchSize) {
                List<KgChunk> batch = chunks.subList(start, Math.min(chunks.size(), start + batchSize));
                for (KgChunk chunk : batch) {
                    if (chunk == null || isBlank(chunk.chunkId())) {
                        continue;
                    }
                    Map<String, Object> chunkParameters = chunkParameters(chunk);
                    var params = Values.value(chunkParameters);
                    session.executeWrite(tx -> {
                        tx.run(chunkUpsertCypher(), params);
                        return null;
                    });
                    chunkCount++;

                    for (KgChunk.KgEntity entity : chunk.entities()) {
                        if (entity == null || isBlank(entity.name())) {
                            continue;
                        }
                        Map<String, Object> entityParameters = entityParameters(chunk, entity);
                        session.executeWrite(tx -> {
                            tx.run(entityUpsertCypher(),
                                    Values.value(entityParameters));
                            return null;
                        });
                        entityCount++;
                    }

                    for (KgChunk.KgRelation relation : chunk.relations()) {
                        if (relation == null || isBlank(relation.source()) || isBlank(relation.target())) {
                            continue;
                        }
                        Map<String, Object> relationParameters = relationParameters(chunk, relation);
                        session.executeWrite(tx -> {
                            tx.run(relationUpsertCypher(), Values.value(relationParameters));
                            return null;
                        });
                        relationCount++;
                        if (!isBlank(String.valueOf(relationParameters.get("connectorHash12")))) {
                            portMappingCount++;
                        }
                    }
                }
            }
            lastWrite = new WriteReport(true, "written", null, chunkCount, entityCount, relationCount, portMappingCount,
                    endpointHost(), null);
            return lastWrite;
        } catch (Exception ex) {
            String failureClass = failureClass(ex);
            log.warn("[AWX][kg][neo4j] status=failed lane={} endpointHost={} failureClass={}",
                    logLane, endpointHost(), failureClass);
            TraceStore.put("retrieval.kg.neo4j.chunkWriter.write.failed", true);
            TraceStore.put("retrieval.kg.neo4j.chunkWriter.write.failureClass", failureClass);
            TraceStore.put("retrieval.kg.neo4j.chunkWriter.write.fallback", "write_failed");
            lastWrite = new WriteReport(true, "failed", "write_failed", chunkCount, entityCount, relationCount,
                    portMappingCount, endpointHost(), failureClass);
            return lastWrite;
        }
    }

    public Map<String, Object> status() {
        String disabled = disabledReason();
        WriteReport write = lastWrite;
        return Map.of(
                "enabled", disabled == null,
                "endpointHost", endpointHost(),
                "disabledReason", disabled == null ? "" : disabled,
                "lastWriteStatus", write.status(),
                "lastWriteFailureClass", write.failureClass() == null ? "" : write.failureClass(),
                "lastWrittenChunks", write.chunkCount(),
                "lastWrittenEntities", write.entityCount(),
                "lastWrittenRelations", write.relationCount(),
                "lastWrittenPortMappings", write.portMappingCount());
    }

    public ManualEvidenceReport readManualEvidence(String domain, int limit) {
        String disabled = disabledReason();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String safeDomain = isBlank(domain) ? "" : normalizeDomain(domain);
        if (disabled != null) {
            return ManualEvidenceReport.disabled(disabled, endpointHost());
        }
        try (Session session = openSession()) {
            List<ManualEvidenceCandidate> candidates = session.executeRead(tx -> tx.run(
                    manualEvidenceCypher(),
                    Values.parameters("domain", safeDomain, "limit", safeLimit)).list(this::manualEvidenceCandidate));
            return new ManualEvidenceReport(true, "ok", "", endpointHost(), candidates.size(), candidates, null);
        } catch (Exception ex) {
            String failureClass = failureClass(ex);
            log.warn("[AWX][graphdb][read] status=failed endpointHost={} failureClass={}",
                    endpointHost(), failureClass);
            TraceStore.put("retrieval.kg.neo4j.chunkWriter.read.failed", true);
            TraceStore.put("retrieval.kg.neo4j.chunkWriter.read.failureClass", failureClass);
            TraceStore.put("retrieval.kg.neo4j.chunkWriter.read.fallback", "read_failed");
            return new ManualEvidenceReport(true, "failed", "read_failed", endpointHost(), 0, List.of(),
                    failureClass);
        }
    }

    public String disabledReason() {
        if (properties == null) {
            return "missing_properties";
        }
        String reason = properties.disabledReason();
        if (reason == null) {
            return null;
        }
        return reason.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    public String endpointHost() {
        return properties == null ? "" : properties.endpointHost();
    }

    @PreDestroy
    public void close() {
        Driver local = driver;
        driver = null;
        if (local != null) {
            local.close();
        }
    }

    private Session openSession() {
        String database = properties.getDatabase();
        if (isBlank(database)) {
            return driver().session();
        }
        return driver().session(SessionConfig.forDatabase(database.trim()));
    }

    private Driver driver() {
        Driver local = driver;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (driver == null) {
                long timeoutMs = Math.max(100L, properties.getTimeoutMs());
                Config config = Config.builder()
                        .withConnectionTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .withMaxTransactionRetryTime(timeoutMs, TimeUnit.MILLISECONDS)
                        .build();
                driver = GraphDatabase.driver(
                        properties.getUri(),
                        AuthTokens.basic(properties.getUser(), properties.getPassword()),
                        config);
            }
            return driver;
        }
    }

    Map<String, Object> chunkParameters(KgChunk chunk) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chunkId", safe(chunk.chunkId(), 128));
        params.put("sessionHash", BrainStateText.hash12(chunk.sessionId()));
        params.put("textHash", BrainStateText.hash12(chunk.sourceText()));
        params.put("textLength", chunk.sourceText() == null ? 0 : chunk.sourceText().length());
        params.put("domain", normalizeDomain(chunk.domain()));
        params.put("sourceTag", safe(defaultString(chunk.sourceTag(), "UNKNOWN"), 80));
        params.put("docType", safe(defaultString(chunk.docType(), "UNKNOWN"), 100));
        params.put("origin", safe(defaultString(chunk.origin(), "UNKNOWN"), 100));
        params.put("ingestLane", safe(defaultString(chunk.ingestLane(), "unknown"), 100));
        params.put("confidence", clamp01(chunk.confidence()));
        params.put("createdAt", chunk.createdAt().toString());
        return Map.copyOf(params);
    }

    Map<String, Object> entityParameters(KgChunk chunk, KgChunk.KgEntity entity) {
        Map<String, Object> params = new LinkedHashMap<>(chunkParameters(chunk));
        params.put("name", safe(entity.name(), 120));
        params.put("domain", normalizeDomain(entity.domain()));
        params.put("type", safe(defaultString(entity.type(), "ENTITY"), 80));
        params.put("confidence", clamp01(entity.confidence()));
        return Map.copyOf(params);
    }

    static String chunkUpsertCypher() {
        return """
                MERGE (c:KgChunkNode {
                    sessionHash: $sessionHash,
                    textHash: $textHash,
                    ingestLane: $ingestLane
                })
                ON CREATE SET c.firstSeenAt = datetime($createdAt)
                SET c.chunkId = $chunkId,
                    c.textLength = $textLength,
                    c.domain = $domain,
                    c.sourceTag = $sourceTag,
                    c.docType = $docType,
                    c.origin = $origin,
                    c.confidence = $confidence,
                    c.lastSeenAt = datetime()
                REMOVE c.`sessionId`
                """;
    }

    static String entityUpsertCypher() {
        return """
                MATCH (c:KgChunkNode {
                    sessionHash: $sessionHash,
                    textHash: $textHash,
                    ingestLane: $ingestLane
                })
                MERGE (e:KgEntity {name: $name, domain: $domain})
                ON CREATE SET e.firstSeenAt = datetime()
                SET e.type = $type,
                    e.confidence = $confidence,
                    e.lastAccessedAt = datetime()
                MERGE (c)-[r:CONTAINS_ENTITY]->(e)
                SET r.confidence = $confidence,
                    r.updatedAt = datetime()
                """;
    }

    static String manualEvidenceCypher() {
        return """
                MATCH (c:KgChunkNode)
                WHERE coalesce(c.ingestLane, '') = 'graphdb_manual_learning'
                  AND ($domain = '' OR c.domain = $domain)
                OPTIONAL MATCH (c)-[:CONTAINS_ENTITY]->(e:KgEntity)
                OPTIONAL MATCH (e)-[r:RELATED_TO {source: 'graphdb_manual_learning'}]->(hop:KgEntity)
                WITH c,
                     [name IN collect(DISTINCT e.name) WHERE name IS NOT NULL][0..6] AS entities,
                      [item IN collect(DISTINCT {
                          target: hop.name,
                          kind: r.kind,
                          connectorHash: r.connectorHash12,
                          pathHash: r.connectorHash12,
                          relationSource: r.source
                      }) WHERE item.target IS NOT NULL][0..6] AS hops
                RETURN c.chunkId AS chunkId,
                       c.sessionHash AS sessionHash,
                       c.textHash AS textHash,
                       c.textLength AS textLength,
                       c.domain AS domain,
                       c.confidence AS confidence,
                       c.sourceTag AS sourceTag,
                       c.docType AS docType,
                       c.origin AS origin,
                       c.ingestLane AS ingestLane,
                       entities,
                       hops
                ORDER BY c.chunkId
                LIMIT $limit
                """;
    }

    static String relationUpsertCypher() {
        return """
                MATCH (a:KgEntity {name: $source, domain: $domain})
                MATCH (b:KgEntity {name: $target, domain: $domain})
                MERGE (a)-[r:RELATED_TO {
                    kind: $kind,
                    connectorHash12: $connectorHash12,
                    source: $relationSource
                }]->(b)
                SET r.confidence = $confidence,
                    r.source = $relationSource,
                    r.sourcePort = $sourcePort,
                    r.targetPort = $targetPort,
                    r.connectorKind = $connectorKind,
                    r.updatedAt = datetime()
                """;
    }

    Map<String, Object> manualEvidenceParameters(String domain, int limit) {
        return Map.of(
                "domain", isBlank(domain) ? "" : normalizeDomain(domain),
                "limit", Math.max(1, Math.min(limit, 50)));
    }

    private ManualEvidenceCandidate manualEvidenceCandidate(Record record) {
        List<String> entityHashes = record.get("entities").asList(value -> safe(value.asString(""), 120)).stream()
                .filter(value -> !value.isBlank())
                .map(BrainStateText::hash12)
                .distinct()
                .toList();
        List<ManualHop> hops = record.get("hops").asList(this::manualHop).stream()
                .filter(hop -> !hop.targetHash().isBlank())
                .toList();
        return new ManualEvidenceCandidate(
                safe(record.get("chunkId").asString(""), 128),
                safe(record.get("sessionHash").asString(""), 32),
                safe(record.get("textHash").asString(""), 64),
                Math.max(0, record.get("textLength").asInt(0)),
                safe(record.get("domain").asString(""), 80),
                clamp01(record.get("confidence").asDouble(0.0d)),
                safe(record.get("sourceTag").asString(""), 80),
                safe(record.get("docType").asString(""), 100),
                safe(record.get("origin").asString(""), 100),
                safe(record.get("ingestLane").asString(""), 100),
                entityHashes,
                hops);
    }

    private ManualHop manualHop(Value value) {
        String target = safe(value.get("target").asString(""), 120);
        String connectorHash = safe(value.get("connectorHash").asString(value.get("pathHash").asString("")), 32);
        return new ManualHop(
                target.isBlank() ? "" : BrainStateText.hash12(target),
                safe(value.get("kind").asString(""), 80),
                safe(value.get("pathHash").asString(""), 32),
                connectorHash,
                safe(value.get("relationSource").asString(""), 100));
    }

    Map<String, Object> relationParameters(KgChunk chunk, KgChunk.KgRelation relation) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", safe(relation.source(), 120));
        params.put("target", safe(relation.target(), 120));
        params.put("domain", normalizeDomain(chunk.domain()));
        params.put("kind", safe(defaultString(relation.kind(), "RELATED_TO"), 80));
        params.put("confidence", clamp01(relation.confidence()));
        params.put("relationSource", relationSource(chunk));
        params.put("sourcePort", GraphRagPortMappingConnector.safePort(
                relation.sourcePort(),
                GraphRagPortMappingConnector.DEFAULT_SOURCE_PORT));
        params.put("targetPort", GraphRagPortMappingConnector.safePort(
                relation.targetPort(),
                GraphRagPortMappingConnector.DEFAULT_TARGET_PORT));
        params.put("connectorKind", safe(defaultString(relation.connectorKind(), relation.kind()), 80));
        params.put("connectorHash12", GraphRagPortMappingConnector.safeHash12(
                relation.connectorHash12(),
                relation.source(),
                relation.sourcePort(),
                relation.target(),
                relation.targetPort(),
                relation.connectorKind()));
        return Map.copyOf(params);
    }

    private static String relationSource(KgChunk chunk) {
        if (chunk != null && !isBlank(chunk.ingestLane())) {
            return safe(chunk.ingestLane(), 100);
        }
        return "conversation-brain-state";
    }

    private static String laneForLog(List<KgChunk> chunks) {
        if (chunks != null) {
            for (KgChunk chunk : chunks) {
                if (chunk != null && !isBlank(chunk.ingestLane())) {
                    return safe(chunk.ingestLane(), 100);
                }
            }
        }
        return "conversation-brain-state";
    }

    private static String normalizeDomain(String domain) {
        String d = defaultString(domain, "GENERAL").trim().toUpperCase(Locale.ROOT);
        return d.isBlank() ? "GENERAL" : safe(d, 80);
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max);
    }

    private static String failureClass(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String className = ex.getClass().getSimpleName();
        String name = className == null ? "" : className.toLowerCase(Locale.ROOT);
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (ex instanceof CancellationException || ex instanceof InterruptedException
                || name.contains("cancel")
                || name.contains("interrupt")
                || message.contains("cancelled")
                || message.contains("canceled")
                || message.contains("interrupted")) {
            return "cancelled";
        }
        return isBlank(className) ? "unknown" : className;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    public record WriteReport(
            boolean enabled,
            String status,
            String disabledReason,
            int chunkCount,
            int entityCount,
            int relationCount,
            int portMappingCount,
            String endpointHost,
            String failureClass) {
        public WriteReport(boolean enabled,
                           String status,
                           String disabledReason,
                           int chunkCount,
                           int entityCount,
                           int relationCount,
                           String endpointHost,
                           String failureClass) {
            this(enabled, status, disabledReason, chunkCount, entityCount, relationCount, 0,
                    endpointHost, failureClass);
        }

        public static WriteReport disabled(String reason) {
            return new WriteReport(false, "disabled", reason, 0, 0, 0, 0, "", null);
        }
    }

    public record ManualEvidenceReport(
            boolean enabled,
            String status,
            String disabledReason,
            String endpointHost,
            int returnedCount,
            List<ManualEvidenceCandidate> candidates,
            String failureClass) {

        public ManualEvidenceReport {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static ManualEvidenceReport disabled(String reason, String endpointHost) {
            return new ManualEvidenceReport(false, "disabled", reason, endpointHost, 0, List.of(), null);
        }
    }

    public record ManualEvidenceCandidate(
            String chunkId,
            String sessionHash,
            String textHash,
            int textLength,
            String domain,
            double confidence,
            String sourceTag,
            String docType,
            String origin,
            String ingestLane,
            List<String> entityHashes,
            List<ManualHop> hops) {

        public ManualEvidenceCandidate {
            sessionHash = safe(sessionHash, 32);
            entityHashes = entityHashes == null ? List.of() : List.copyOf(entityHashes);
            hops = hops == null ? List.of() : List.copyOf(hops);
        }
    }

    public record ManualHop(String targetHash, String kind, String pathHash, String connectorHash, String relationSource) {
        public ManualHop(String targetHash, String kind, String pathHash) {
            this(targetHash, kind, pathHash, pathHash, "");
        }

        public ManualHop(String targetHash, String kind, String pathHash, String relationSource) {
            this(targetHash, kind, pathHash, pathHash, relationSource);
        }

        public ManualHop {
            targetHash = safe(targetHash, 32);
            kind = safe(kind, 80);
            pathHash = safe(pathHash, 32);
            connectorHash = safe(connectorHash, 32);
            relationSource = safe(relationSource, 100);
        }
    }
}
