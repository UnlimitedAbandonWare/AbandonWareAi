package com.example.lms.service.rag.kg;

import com.example.lms.search.TraceStore;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

public class Neo4jKnowledgeGraphClient {

    private static final Logger log = LoggerFactory.getLogger(Neo4jKnowledgeGraphClient.class);
    private static final String NEO4J_QUERY_FAILED = "neo4j_query_failed";
    private static final String NEO4J_UPSERT_FAILED = "neo4j_upsert_failed";
    private static final String NEO4J_RECOMMEND_FAILED = "neo4j_recommend_failed";
    static final String LOOKUP_RELATION_QUERY = """
            MATCH (e:KgEntity {domain: $domain})-[r:KG_REL|RELATED_TO]->(target:KgEntity {domain: $domain})
            WHERE toLower(e.name) IN $entities
            RETURN e.name AS entity,
                   coalesce(e.type, '') AS entityType,
                   coalesce(e.confidence, 1.0) AS entityConfidence,
                   e.lastAccessedAt AS lastAccessedAt,
                   coalesce(r.kind, 'RELATIONSHIP_ASSOCIATED_WITH') AS kind,
                   coalesce(r.confidence, 1.0) AS relationshipConfidence,
                   coalesce(r.source, 'neo4j') AS relationshipSource,
                   target.name AS target
            LIMIT $limit
            """;

    private final Neo4jKnowledgeGraphProperties properties;
    private volatile Driver driver;

    public Neo4jKnowledgeGraphClient(Neo4jKnowledgeGraphProperties properties) {
        this.properties = properties;
    }

    public boolean isConfiguredEnabled() {
        return properties != null && properties.isEnabled();
    }

    public boolean hasPassword() {
        return properties != null && properties.hasPassword();
    }

    public String endpointHost() {
        return properties == null ? "" : properties.endpointHost();
    }

    public String disabledReason() {
        return properties == null ? Neo4jKnowledgeGraphProperties.REASON_MISSING_PROPERTIES : properties.disabledReason();
    }

    public List<Neo4jKgEntry> lookup(String domain, Set<String> entities, int limit) {
        TraceStore.put("retrieval.kg.neo4j.failed", false);
        TraceStore.put("retrieval.kg.neo4j.failureClass", null);
        TraceStore.put("retrieval.kg.neo4j.fallback", null);
        String disabledReason = disabledReason();
        if (disabledReason != null || domain == null || domain.isBlank() || entities == null || entities.isEmpty()) {
            return List.of();
        }
        try (Session session = openSession()) {
            List<String> entityKeys = entities.stream()
                    .filter(e -> e != null && !e.isBlank())
                    .map(e -> e.trim().toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
            if (entityKeys.isEmpty()) {
                return List.of();
            }
            int entityLimit = Math.max(1, Math.min(limit, 100));
            int rowLimit = Math.max(entityLimit * 8, entityLimit);
            return session.executeRead(tx -> {
                var result = tx.run(LOOKUP_RELATION_QUERY,
                        Values.parameters(
                                "domain", domain,
                                "entities", entityKeys,
                                "limit", rowLimit));
                Map<String, MutableEntry> grouped = new LinkedHashMap<>();
                while (result.hasNext()) {
                    Record record = result.next();
                    addRecord(grouped, record);
                }
                return grouped.values().stream()
                        .limit(entityLimit)
                        .map(MutableEntry::toEntry)
                        .toList();
            });
        } catch (Exception ex) {
            String failureClass = failureClass(ex);
            log.warn("[AWX2AF2][search][neo4j] provider=neo4j enabled={} endpointHost={} disabledReason={} failureClass={}",
                    isConfiguredEnabled(), endpointHost(), NEO4J_QUERY_FAILED, failureClass);
            TraceStore.put("retrieval.kg.neo4j.failed", true);
            TraceStore.put("retrieval.kg.neo4j.failureClass", failureClass);
            TraceStore.put("retrieval.kg.neo4j.fallback", "empty_list");
            return List.of();
        }
    }

    public void upsertFailurePattern(
            String patternId,
            String dominantFailure,
            String hotspot,
            String restoreAction,
            double confidence,
            double riskScore,
            int matrixTile,
            double jbPressure,
            double cbPressure) {
        String disabledReason = disabledReason();
        String safePatternId = safeLabel(patternId, 96, "");
        if (disabledReason != null || safePatternId.isBlank()) {
            return;
        }
        String safeFailure = safeLabel(dominantFailure, 80, "none");
        String safeHotspot = safeLabel(hotspot, 80, "none");
        String safeAction = safeLabel(restoreAction, 80, "observe_only");
        int safeTile = Math.max(0, Math.min(99, matrixTile));
        String jbBucket = pressureBucket(jbPressure);
        String cbBucket = pressureBucket(cbPressure);
        String conditionKey = "tile:" + safeTile + "|jb:" + jbBucket + "|cb:" + cbBucket;
        try (Session session = openSession()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (p:RagFailurePattern {patternId: $patternId})
                        ON CREATE SET p.firstSeenAt = datetime()
                        SET p.dominantFailure = $dominantFailure,
                            p.hotspot = $hotspot,
                            p.restoreAction = $restoreAction,
                            p.confidence = $confidence,
                            p.riskScore = $riskScore,
                            p.matrixTile = $matrixTile,
                            p.jbBucket = $jbBucket,
                            p.cbBucket = $cbBucket,
                            p.lastSeenAt = datetime(),
                            p.count = coalesce(p.count, 0) + 1
                        MERGE (c:RagComponent {name: $hotspot})
                        MERGE (cond:FailureCondition {key: $conditionKey})
                        SET cond.matrixTile = $matrixTile,
                            cond.jbBucket = $jbBucket,
                            cond.cbBucket = $cbBucket
                        MERGE (s:FailureSymptom {failureClass: $dominantFailure})
                        MERGE (a:RecoveryAction {name: $restoreAction})
                        MERGE (t:MatrixTile {tile: $matrixTile})
                        MERGE (p)-[:OBSERVED_IN]->(c)
                        MERGE (p)-[:OBSERVED_IN]->(t)
                        MERGE (p)-[:UNDER_CONDITION]->(cond)
                        MERGE (p)-[:CAUSED_SYMPTOM]->(s)
                        MERGE (p)-[r:RECOMMENDS]->(a)
                        SET r.confidence = $confidence,
                            r.updatedAt = datetime()
                        MERGE (c)-[:CAUSED_SYMPTOM]->(s)
                        """,
                        Values.parameters(
                                "patternId", safePatternId,
                                "dominantFailure", safeFailure,
                                "hotspot", safeHotspot,
                                "restoreAction", safeAction,
                                "confidence", clamp01(confidence),
                                "riskScore", clamp01(riskScore),
                                "matrixTile", safeTile,
                                "jbBucket", jbBucket,
                                "cbBucket", cbBucket,
                                "conditionKey", conditionKey));
                return null;
            });
        } catch (Exception ex) {
            String failureClass = failureClass(ex);
            log.warn("[AWX2AF2][graph][failure] provider=neo4j enabled={} endpointHost={} disabledReason={} failureClass={}",
                    isConfiguredEnabled(), endpointHost(), NEO4J_UPSERT_FAILED, failureClass);
            TraceStore.put("retrieval.kg.neo4j.upsert.failed", true);
            TraceStore.put("retrieval.kg.neo4j.upsert.failureClass", failureClass);
            TraceStore.put("retrieval.kg.neo4j.upsert.fallback", "skip_write");
        }
    }

    public List<RecoveryRecommendation> recommendRecovery(
            String patternId,
            String dominantFailure,
            String hotspot,
            int matrixTile,
            double jbPressure,
            double cbPressure,
            int topK) {
        String disabledReason = disabledReason();
        if (disabledReason != null) {
            return List.of();
        }
        String safePatternId = safeLabel(patternId, 96, "");
        String safeFailure = safeLabel(dominantFailure, 80, "none");
        String safeHotspot = safeLabel(hotspot, 80, "none");
        int safeTile = Math.max(0, Math.min(99, matrixTile));
        int limit = Math.max(1, Math.min(10, topK));
        String jbBucket = pressureBucket(jbPressure);
        String cbBucket = pressureBucket(cbPressure);
        try (Session session = openSession()) {
            return session.executeRead(tx -> {
                var exact = tx.run("""
                        MATCH (p:RagFailurePattern {patternId: $patternId})-[r:RECOMMENDS]->(a:RecoveryAction)
                        RETURN a.name AS restoreAction,
                               p.patternId AS patternId,
                               coalesce(r.confidence, p.confidence, 0.0) AS confidence,
                               'exact_pattern' AS reason
                        ORDER BY confidence DESC
                        LIMIT $limit
                        """,
                        Values.parameters("patternId", safePatternId, "limit", limit));
                List<RecoveryRecommendation> out = recommendationsFromResult(exact);
                if (out.stream().anyMatch(Neo4jKnowledgeGraphClient::actionableRecommendation)) {
                    return out;
                }
                var fallback = tx.run("""
                        MATCH (p:RagFailurePattern)-[r:RECOMMENDS]->(a:RecoveryAction)
                        WHERE p.dominantFailure = $dominantFailure
                           OR p.hotspot = $hotspot
                           OR p.matrixTile = $matrixTile
                           OR p.jbBucket = $jbBucket
                           OR p.cbBucket = $cbBucket
                        WITH p, r, a,
                             (CASE WHEN p.dominantFailure = $dominantFailure THEN 0.35 ELSE 0.0 END) +
                             (CASE WHEN p.hotspot = $hotspot THEN 0.20 ELSE 0.0 END) +
                             (CASE WHEN p.matrixTile = $matrixTile THEN 0.20 ELSE 0.0 END) +
                             (CASE WHEN p.jbBucket = $jbBucket THEN 0.10 ELSE 0.0 END) +
                             (CASE WHEN p.cbBucket = $cbBucket THEN 0.10 ELSE 0.0 END) +
                             (coalesce(r.confidence, p.confidence, 0.0) * 0.05) AS score
                        RETURN a.name AS restoreAction,
                               p.patternId AS patternId,
                               score AS confidence,
                               'similar_failure_path' AS reason
                        ORDER BY confidence DESC
                        LIMIT $limit
                        """,
                        Values.parameters(
                                "dominantFailure", safeFailure,
                                "hotspot", safeHotspot,
                                "matrixTile", safeTile,
                                "jbBucket", jbBucket,
                                 "cbBucket", cbBucket,
                                 "limit", limit));
                List<RecoveryRecommendation> fallbackOut = recommendationsFromResult(fallback);
                return fallbackOut.isEmpty() ? out : fallbackOut;
            });
        } catch (Exception ex) {
            String failureClass = failureClass(ex);
            log.warn("[AWX2AF2][graph][failure] provider=neo4j enabled={} endpointHost={} disabledReason={} failureClass={}",
                    isConfiguredEnabled(), endpointHost(), NEO4J_RECOMMEND_FAILED, failureClass);
            TraceStore.put("retrieval.kg.neo4j.recommend.failed", true);
            TraceStore.put("retrieval.kg.neo4j.recommend.failureClass", failureClass);
            TraceStore.put("retrieval.kg.neo4j.recommend.fallback", "empty_list");
            return List.of();
        }
    }

    private static String failureClass(Exception ex) {
        Throwable root = rootCause(ex);
        String className = root == null ? "" : root.getClass().getSimpleName();
        String lowerClass = className.toLowerCase(Locale.ROOT);
        String message = root == null || root.getMessage() == null
                ? ""
                : root.getMessage().toLowerCase(Locale.ROOT);
        if (root instanceof CancellationException
                || root instanceof InterruptedException
                || lowerClass.contains("cancel")
                || lowerClass.contains("interrupt")
                || message.contains("cancelled")
                || message.contains("canceled")
                || message.contains("interrupted")) {
            return "cancelled";
        }
        return className;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && current.getCause() != current && depth++ < 8) {
            current = current.getCause();
        }
        return current;
    }

    private Session openSession() {
        String database = properties.getDatabase();
        if (database == null || database.isBlank()) {
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
                Config config = Config.builder()
                        .withConnectionTimeout(Math.max(1L, properties.getTimeoutMs()), TimeUnit.MILLISECONDS)
                        .build();
                driver = GraphDatabase.driver(
                        properties.getUri(),
                        AuthTokens.basic(properties.getUser(), properties.getPassword()),
                        config);
            }
            return driver;
        }
    }

    @PreDestroy
    public void close() {
        Driver local = driver;
        driver = null;
        if (local != null) {
            local.close();
        }
    }

    private static List<RecoveryRecommendation> recommendationsFromResult(org.neo4j.driver.Result result) {
        List<RecoveryRecommendation> out = new ArrayList<>();
        while (result.hasNext()) {
            Record record = result.next();
            String action = safeString(record, "restoreAction");
            if (action.isBlank()) {
                continue;
            }
            out.add(new RecoveryRecommendation(
                    action,
                    safeString(record, "patternId"),
                    clamp01(safeDouble(record, "confidence", 0.0)),
                    safeString(record, "reason")));
        }
        return List.copyOf(out);
    }

    private static boolean actionableRecommendation(RecoveryRecommendation recommendation) {
        if (recommendation == null) {
            return false;
        }
        String action = safeLabel(recommendation.restoreAction(), 80, "");
        return !action.isBlank()
                && !"observe_only".equalsIgnoreCase(action)
                && !"none".equalsIgnoreCase(action);
    }

    private static void addRecord(Map<String, MutableEntry> grouped, Record record) {
        String entity = safeString(record, "entity");
        String target = safeString(record, "target");
        if (entity.isBlank() || target.isBlank()) {
            return;
        }
        MutableEntry entry = grouped.computeIfAbsent(entity, key -> new MutableEntry(
                entity,
                safeString(record, "entityType"),
                safeDouble(record, "entityConfidence", 1.0),
                safeInstant(record, "lastAccessedAt")));
        String kind = safeString(record, "kind");
        if (kind.isBlank()) {
            kind = "RELATIONSHIP_ASSOCIATED_WITH";
        }
        entry.relationships.computeIfAbsent(kind, ignored -> new LinkedHashSet<>()).add(target);
        entry.relationshipConfidence = Math.max(entry.relationshipConfidence,
                safeDouble(record, "relationshipConfidence", 1.0));
        String source = safeString(record, "relationshipSource");
        if (!source.isBlank()) {
            entry.sources.add(source);
        }
    }

    private static String safeString(Record record, String key) {
        try {
            return record.get(key).asString("");
        } catch (Exception ignore) {
            traceValueParseSuppressed("safe_string");
            log.debug("[AWX][rag][kg] neo4j value parse skipped stage=safe_string err=parse-failure"); return "";
        }
    }

    private static double safeDouble(Record record, String key, double fallback) {
        try {
            return record.get(key).asDouble(fallback);
        } catch (Exception ignore) {
            traceValueParseSuppressed("safe_double");
            log.debug("[AWX][rag][kg] neo4j value parse skipped stage=safe_double err=parse-failure"); return fallback;
        }
    }

    private static Instant safeInstant(Record record, String key) {
        try {
            Object value = record.get(key).asObject();
            if (value instanceof Instant instant) {
                return instant;
            }
            if (value != null) {
                return Instant.parse(String.valueOf(value));
            }
        } catch (Exception ex) {
            traceValueParseSuppressed("safe_instant");
            log.debug("[AWX][rag][kg] neo4j value parse skipped stage=safe_instant err=parse-failure");
        }
        return Instant.now();
    }

    private static void traceValueParseSuppressed(String stage) {
        TraceStore.put("retrieval.kg.neo4j.valueParse.suppressed." + stage, true);
        TraceStore.put("retrieval.kg.neo4j.valueParse." + stage + ".reason", "parse-failure");
        TraceStore.put("retrieval.kg.neo4j.valueParse." + stage + ".errorType", "parse_failure");
    }

    private static String safeLabel(String value, int max, String fallback) {
        String v = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (v.isBlank()) {
            v = fallback == null ? "" : fallback;
        }
        if (v.length() <= max) {
            return v;
        }
        return v.substring(0, max);
    }

    private static String pressureBucket(double value) {
        double v = clamp01(value);
        if (v >= 0.66d) {
            return "high";
        }
        if (v >= 0.33d) {
            return "medium";
        }
        return "low";
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    private static final class MutableEntry {
        private final String entity;
        private final String entityType;
        private final double entityConfidence;
        private final Instant lastAccessedAt;
        private final Map<String, Set<String>> relationships = new LinkedHashMap<>();
        private final List<String> sources = new ArrayList<>();
        private double relationshipConfidence = 0.0;

        private MutableEntry(String entity, String entityType, double entityConfidence, Instant lastAccessedAt) {
            this.entity = entity;
            this.entityType = entityType;
            this.entityConfidence = entityConfidence;
            this.lastAccessedAt = lastAccessedAt;
        }

        private Neo4jKgEntry toEntry() {
            return new Neo4jKgEntry(
                    entity,
                    entityType,
                    Math.max(entityConfidence, relationshipConfidence),
                    lastAccessedAt,
                    relationships,
                    sources);
        }
    }

    public record Neo4jKgEntry(
            String entity,
            String entityType,
            double confidence,
            Instant lastAccessedAt,
            Map<String, Set<String>> relationships,
            List<String> sources) {
    }

    public record RecoveryRecommendation(
            String restoreAction,
            String patternId,
            double confidence,
            String reason) {
    }
}
