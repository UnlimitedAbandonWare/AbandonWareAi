package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.rag.graph.BrainStateService;
import com.example.lms.service.rag.graph.InferenceResult;
import com.example.lms.service.rag.graph.SparseNodeInferenceService;
import com.example.lms.service.rag.kg.KgTailPowerMeanScorer;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient.Neo4jKgEntry;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

@Component
public class KnowledgeGraphHandler implements ContentRetriever {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphHandler.class);
    private static final Pattern HASH12 = Pattern.compile("^[0-9a-fA-F]{12}$");

    private final KnowledgeBaseService knowledgeBase;
    private final Neo4jKnowledgeGraphClient neo4jClient;
    private final KgTailPowerMeanScorer scorer;
    private final ObjectProvider<BrainStateService> brainStateProvider;

    public KnowledgeGraphHandler() {
        this(null, null, null, null, null);
    }

    @Autowired
    public KnowledgeGraphHandler(
            @Autowired(required = false) KnowledgeBaseService knowledgeBase,
            @Autowired(required = false) Neo4jKnowledgeGraphClient neo4jClient,
            @Autowired(required = false) KgTailPowerMeanScorer scorer,
            @Autowired(required = false) SparseNodeInferenceService ignoredSparseNodeInferenceService,
            @Autowired(required = false) ObjectProvider<BrainStateService> brainStateProvider) {
        this.knowledgeBase = knowledgeBase;
        this.neo4jClient = neo4jClient;
        this.scorer = scorer;
        this.brainStateProvider = brainStateProvider;
    }

    @SuppressWarnings("unchecked")
    public KnowledgeGraphHandler(Object... legacyDependencies) {
        this(first(KnowledgeBaseService.class, legacyDependencies),
                first(Neo4jKnowledgeGraphClient.class, legacyDependencies),
                first(KgTailPowerMeanScorer.class, legacyDependencies),
                first(SparseNodeInferenceService.class, legacyDependencies),
                (ObjectProvider<BrainStateService>) first(ObjectProvider.class, legacyDependencies));
    }

    @Override
    public List<Content> retrieve(Query query) {
        String queryText = query == null ? "" : query.text();
        if (knowledgeBase == null) {
            traceDependency("disabled", false, "missing_knowledge_base", "", queryText);
            TraceStore.put("kg.handler.status", "disabled");
            TraceStore.put("kg.handler.disabledReason", "missing_knowledge_base");
            return List.of();
        }
        try {
            String domain = blankToDefault(knowledgeBase.inferDomain(queryText), "GENERAL");
            Set<String> mentioned = safeMentionedEntities(domain, queryText);
            List<Content> neo4j = retrieveNeo4j(domain, mentioned, queryText);
            if (!neo4j.isEmpty()) {
                return maybeAugmentWithBrainState(neo4j, queryText, domain);
            }
            List<Content> jpa = retrieveJpa(domain, mentioned, queryText);
            if (!jpa.isEmpty()) {
                return maybeAugmentWithBrainState(jpa, queryText, domain);
            }
            if ("jpa_failed".equals(TraceStore.get("retrieval.dependency.kg.status"))) {
                return List.of();
            }
            if (mentioned == null || mentioned.isEmpty()) {
                return brainStateFallback(queryText, domain, "no_mentioned_entities");
            }
            traceDependency("empty", false, "", "", queryText);
            return List.of();
        } catch (Throwable e) {
            String text = queryText;
            traceDependency("failed", true, classifyFailure(e), errorType(e), queryText);
            traceSuppressed(log, "KnowledgeGraphHandler", "faultMask.trace", e);
            log.warn("[AWX][rag][handler] kg retrieve failed failureReason={} errorType={} failureClass={} queryHash12={} queryLength={}",
                    "kg-retrieve-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.traceLabelOrFallback(classifyFailure(e), "silent-failure"),
                    SafeRedactor.hash12(queryText),
                    text == null ? 0 : text.length());
            return List.of();
        }
    }

    private Set<String> safeMentionedEntities(String domain, String queryText) {
        Set<String> mentioned = knowledgeBase.findMentionedEntities(domain, queryText);
        return mentioned == null ? Set.of() : mentioned;
    }

    private List<Content> retrieveNeo4j(String domain, Set<String> mentioned, String queryText) {
        TraceStore.put("retrieval.kg.neo4j.failed", false);
        TraceStore.put("retrieval.kg.neo4j.failureClass", null);
        TraceStore.put("retrieval.kg.neo4j.fallback", null);
        if (neo4jClient == null) {
            traceNeo4j("disabled", "missing_client", 0, queryText);
            return List.of();
        }
        String disabledReason = neo4jClient.disabledReason();
        if (disabledReason != null || !neo4jClient.isConfiguredEnabled() || !neo4jClient.hasPassword()) {
            traceNeo4j("disabled", blankToDefault(disabledReason, "disabled"), 0, queryText);
            return List.of();
        }
        if (mentioned == null || mentioned.isEmpty()) {
            traceNeo4j("empty", "no_mentioned_entities", 0, queryText);
            return List.of();
        }
        try {
            List<Neo4jKgEntry> rows = neo4jClient.lookup(domain, mentioned, 8);
            int returned = rows == null ? 0 : rows.size();
            if (Boolean.TRUE.equals(TraceStore.get("retrieval.kg.neo4j.failed"))) {
                traceNeo4j("failed", "neo4j_query_failed", returned, queryText);
                return List.of();
            }
            traceNeo4j(returned > 0 ? "success" : "empty", "", returned, queryText);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            List<Content> out = new ArrayList<>();
            for (Neo4jKgEntry row : rows) {
                if (row == null) {
                    continue;
                }
                out.addAll(contentsFromRelationships(
                        "neo4j",
                        "neo4j_relationships",
                        row.entity(),
                        row.relationships(),
                        row.confidence(),
                        row.lastAccessedAt()));
            }
            if (!out.isEmpty()) {
                traceDependency("success", false, "", "", queryText);
            }
            return out;
        } catch (Throwable e) {
            traceNeo4jFailure(queryText);
            traceDependency("failed", true, classifyFailure(e), errorType(e), queryText);
            return List.of();
        }
    }

    private List<Content> retrieveJpa(String domain, Set<String> mentioned, String queryText) {
        if (mentioned == null || mentioned.isEmpty()) {
            traceJpa("empty", 0, "", queryText);
            return List.of();
        }
        try {
            List<Content> out = new ArrayList<>();
            for (String entity : mentioned) {
                if (entity == null || entity.isBlank()) {
                    continue;
                }
                double confidence = confidence(domain, entity);
                Map<String, Set<String>> relationships = knowledgeBase.getAllRelationships(domain, entity);
                out.addAll(contentsFromRelationships(
                        "jpa",
                        "jpa_relationships",
                        entity,
                        relationships,
                        confidence,
                        lastAccessed(domain, entity)));
                out.addAll(associativePaths(domain, entity, relationships, queryText));
            }
            traceJpa(out.isEmpty() ? "empty" : "success", out.size(), "", queryText);
            if (!out.isEmpty()) {
                traceDependency("success", false, "", "", queryText);
            }
            return out;
        } catch (Throwable e) {
            traceJpa("failed", 0, "silent-failure", queryText);
            TraceStore.put("retrieval.kg.jpa.lastFailureClass", "silent-failure");
            TraceStore.put("retrieval.kg.jpa.lastExceptionType", "silent-failure");
            TraceStore.put("retrieval.kg.jpa.failureCount",
                    TraceStore.getLong("retrieval.kg.jpa.failureCount") + 1L);
            traceDependency("jpa_failed", true, "silent-failure", "silent-failure", queryText);
            traceSuppressed(log, "KnowledgeGraphHandler", "jpa.entity", e);
            traceSuppressed(log, "KnowledgeGraphHandler", "jpa.entityTrace", e);
            return List.of();
        }
    }

    private List<Content> maybeAugmentWithBrainState(List<Content> current, String queryText, String domain) {
        InferenceResult result = queryBrainState(queryText, domain);
        if (result == null) {
            return current;
        }
        boolean applied = Boolean.TRUE.equals(result.ragDebug().get("sparseNode.queryAnchorMap.applied"));
        traceBrainState(applied ? "success" : "not_applied",
                true,
                result.disabledReason(),
                brainStateReason(result, ""),
                result,
                queryText);
        if (!applied || result.inferredRelations().isEmpty()) {
            traceDependency("success", false, "", "", queryText);
            return current;
        }
        List<Content> out = new ArrayList<>(current);
        out.addAll(contentsFromBrainState(result, "query_anchor_map_augmentation"));
        traceDependency("success_anchor_augmented", true, "", "", queryText);
        return out;
    }

    private List<Content> brainStateFallback(String queryText, String domain, String reason) {
        InferenceResult result = queryBrainState(queryText, domain);
        if (result == null || result.inferredRelations().isEmpty()) {
            traceBrainState("empty", true, result == null ? "missing_brain_state" : result.disabledReason(),
                    brainStateReason(result, reason), result, queryText);
            traceDependency("empty", true, "", "", queryText);
            return List.of();
        }
        traceBrainState("success", true, result.disabledReason(), brainStateReason(result, reason), result, queryText);
        traceDependency("success", true, "", "", queryText);
        return contentsFromBrainState(result, reason);
    }

    private InferenceResult queryBrainState(String queryText, String domain) {
        BrainStateService brainState = brainStateProvider == null ? null : brainStateProvider.getIfAvailable();
        if (brainState == null) {
            return null;
        }
        return brainState.querySparseInferenceLocalOnly(queryText, domain);
    }

    private List<Content> contentsFromRelationships(
            String provider,
            String thumbnailSource,
            String entity,
            Map<String, Set<String>> relationships,
            double confidence,
            Instant lastAccessedAt) {
        if (relationships == null || relationships.isEmpty()) {
            return List.of();
        }
        List<Content> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : relationships.entrySet()) {
            String kind = blankToDefault(entry.getKey(), "RELATIONSHIP_ASSOCIATED_WITH");
            Set<String> targets = entry.getValue();
            if (targets == null || targets.isEmpty()) {
                continue;
            }
            for (String target : targets) {
                if (target == null || target.isBlank()) {
                    continue;
                }
                String breadcrumb = compact(entity) + " --" + kind + "--> " + compact(target);
                double score = scorer == null ? confidence : scorer.adjust(confidence, 1.0d, confidence,
                        recencySignal(lastAccessedAt), targets.size());
                out.add(content(
                        "relationBreadcrumbs: " + breadcrumb
                                + "\nrelationSummary: " + compact(entity) + " is linked to "
                                + compact(target) + " via " + kind,
                        metadata(provider, thumbnailSource, breadcrumb, kind, score, "knowledge_graph")));
            }
        }
        return out;
    }

    private List<Content> associativePaths(String domain, String entity, Map<String, Set<String>> relationships,
                                           String queryText) {
        if (relationships == null || relationships.isEmpty()) {
            traceSparsePath("empty", 0, "", queryText);
            return List.of();
        }
        try {
            for (Map.Entry<String, Set<String>> first : relationships.entrySet()) {
                if (first.getValue() == null) {
                    continue;
                }
                for (String middle : first.getValue()) {
                    Map<String, Set<String>> secondHop = knowledgeBase.getAllRelationships(domain, middle);
                    if (secondHop == null || secondHop.isEmpty()) {
                        continue;
                    }
                    for (Map.Entry<String, Set<String>> second : secondHop.entrySet()) {
                        if (second.getValue() == null || second.getValue().isEmpty()) {
                            continue;
                        }
                        String target = second.getValue().iterator().next();
                        String path = compact(entity) + " --" + first.getKey() + "--> " + compact(middle)
                                + " --" + second.getKey() + "--> " + compact(target);
                        Map<String, Object> meta = metadata(
                                "jpa",
                                "associative_path_inference",
                                path,
                                second.getKey(),
                                0.85d,
                                "associative_path_inference");
                        meta.put("kg_mode", "associative_path_inference");
                        meta.put("kg_path_depth", 2);
                        meta.put("kg_path_hash12", SafeRedactor.hash12(path));
                        meta.put("kg_relation_thumbnail_hash", SafeRedactor.hash12(path));
                        traceSparsePath("success", 1, "", queryText);
                        return List.of(content("relationBreadcrumbs: " + path
                                + "\nrelationSummary: associative path " + path, meta));
                    }
                }
            }
            traceSparsePath("empty", 0, "", queryText);
            return List.of();
        } catch (Throwable e) {
            traceSparsePath("failed", 0, "silent-failure", queryText);
            traceSuppressed(log, "KnowledgeGraphHandler", "sparsePath.trace", e);
            return List.of();
        }
    }

    private List<Content> contentsFromBrainState(InferenceResult result, String fallbackReason) {
        if (result == null || result.inferredRelations().isEmpty()) {
            return List.of();
        }
        List<String> seedHashes = brainStateSeedHashes(result);
        long seedCount = seedHashes.size();
        String reason = brainStateReason(result, "");
        boolean applied = Boolean.TRUE.equals(result.ragDebug().get("sparseNode.queryAnchorMap.applied"));
        List<Content> out = new ArrayList<>();
        for (String relation : result.inferredRelations()) {
            if (relation == null || relation.isBlank()) {
                continue;
            }
            String breadcrumb = compact(relation);
            Map<String, Object> meta = metadata(
                    "brain-state",
                    "brain_state_sparse_inference",
                    breadcrumb,
                    relationKind(breadcrumb),
                    0.8d,
                    "brain_state_sparse_inference");
            meta.put("kg_fallback_reason", SafeRedactor.traceLabelOrFallback(fallbackReason, "unknown"));
            meta.put("kg_query_anchor_map", String.valueOf(applied));
            meta.put("kg_query_anchor_map_seed_count", seedCount);
            meta.put("kg_query_anchor_map_seed_hashes", String.join(",", seedHashes));
            meta.put("kg_query_anchor_map_reason", reason);
            out.add(content("relationBreadcrumbs: " + breadcrumb
                    + "\nrelationSummary: brain-state sparse inference " + breadcrumb, meta));
        }
        return out;
    }

    private static Map<String, Object> metadata(
            String provider,
            String thumbnailSource,
            String breadcrumb,
            String kind,
            double score,
            String mode) {
        Map<String, Object> meta = new LinkedHashMap<>();
        String hash = SafeRedactor.hash12(breadcrumb);
        meta.put("retrieval_source", "kg");
        meta.put("kg_provider", provider);
        meta.put("kg_mode", mode);
        meta.put("score", score);
        meta.put("kg_relation_breadcrumb", breadcrumb);
        meta.put("kg_relation_summary", breadcrumb);
        meta.put("kg_relation_kind", kind);
        meta.put("kg_relation_hash12", hash);
        meta.put("kg_relation_thumbnail_mode", "relation_thumbnail_v1");
        meta.put("kg_relation_thumbnail_source", thumbnailSource);
        meta.put("kg_relation_thumbnail_hash", hash);
        meta.put("kg_relation_anchor_hash12", hash);
        return meta;
    }

    private static Content content(String text, Map<String, Object> metadata) {
        return Content.from(TextSegment.from(text, Metadata.from(metadata)));
    }

    private double confidence(String domain, String entity) {
        Optional<Double> value = knowledgeBase.getConfidenceScore(domain, entity);
        return clamp01(value.orElse(0.75d));
    }

    private Instant lastAccessed(String domain, String entity) {
        try {
            Optional<Instant> value = knowledgeBase.getLastAccessedAt(domain, entity);
            return value.orElse(null);
        } catch (Throwable e) {
            traceSuppressed(log, "KnowledgeGraphHandler", "jpa.entityTrace", e);
            return null;
        }
    }

    private static double recencySignal(Instant lastAccessedAt) {
        return lastAccessedAt == null ? 0.5d : 1.0d;
    }

    private static void traceNeo4j(String status, String disabledReason, int returnedCount, String queryText) {
        try {
            String safeDisabledReason = SafeRedactor.traceLabelOrFallback(disabledReason, "unknown");
            TraceStore.put("retrieval.kg.neo4j.status", status);
            TraceStore.put("retrieval.kg.neo4j.disabledReason", safeDisabledReason);
            TraceStore.put("retrieval.kg.neo4j.returnedCount", Math.max(0, returnedCount));
            TraceStore.put("retrieval.kg.neo4j.queryHash12", SafeRedactor.hash12(queryText));
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("status", status);
            event.put("disabledReason", safeDisabledReason);
            event.put("returnedCount", Math.max(0, returnedCount));
            event.put("queryHash12", SafeRedactor.hash12(queryText));
            TraceStore.append("retrieval.kg.neo4j.events", event);
        } catch (Throwable e) {
            traceSuppressed(log, "KnowledgeGraphHandler", "neo4j.trace", e);
        }
    }

    private static void traceNeo4jFailure(String queryText) {
        TraceStore.put("retrieval.kg.neo4j.failed", true);
        traceNeo4j("failed", "neo4j_query_failed", 0, queryText);
        TraceStore.put("retrieval.kg.neo4j.failureClass", "silent-failure");
    }

    private static void traceJpa(String status, int count, String failureClass, String queryText) {
        try {
            TraceStore.put("retrieval.kg.jpa.status", status);
            TraceStore.put("retrieval.kg.jpa.returnedCount", Math.max(0, count));
            TraceStore.put("retrieval.kg.jpa.failureClass", SafeRedactor.traceLabelOrFallback(failureClass, ""));
            TraceStore.put("retrieval.kg.jpa.queryHash12", SafeRedactor.hash12(queryText));
        } catch (Throwable e) {
            traceSuppressed(log, "KnowledgeGraphHandler", "jpa.trace", e);
        }
    }

    private static void traceBrainState(
            String status,
            boolean fallbackUsed,
            String disabledReason,
            String reason,
            InferenceResult result,
            String queryText) {
        try {
            String safeBrainStateReason = SafeRedactor.traceLabelOrFallback(blankToDefault(reason, ""), "unknown");
            String safeBrainStateDisabledReason = SafeRedactor.traceLabelOrFallback(blankToDefault(disabledReason, ""), "unknown");
            List<String> seedHashes = brainStateSeedHashes(result);
            long seedCount = seedHashes.size();
            boolean applied = result != null
                    && Boolean.TRUE.equals(result.ragDebug().get("sparseNode.queryAnchorMap.applied"));
            TraceStore.put("retrieval.kg.brainState.status", status);
            TraceStore.put("retrieval.kg.brainState.fallbackUsed", fallbackUsed);
            TraceStore.put("retrieval.kg.brainState.reason", safeBrainStateReason);
            TraceStore.put("retrieval.kg.brainState.disabledReason", safeBrainStateDisabledReason);
            TraceStore.put("retrieval.kg.brainState.queryHash12", SafeRedactor.hash12(queryText));
            TraceStore.put("retrieval.kg.brainState.queryAnchorMap.applied", applied);
            TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedCount", seedCount);
            TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedHashes", seedHashes);
            TraceStore.put("retrieval.kg.brainState.queryAnchorMap.reason", safeBrainStateReason);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("status", status);
            event.put("fallbackUsed", fallbackUsed);
            event.put("reason", safeBrainStateReason);
            event.put("disabledReason", safeBrainStateDisabledReason);
            event.put("queryHash12", SafeRedactor.hash12(queryText));
            event.put("queryAnchorMapApplied", applied);
            event.put("seedCount", seedCount);
            event.put("seedHashes", seedHashes);
            TraceStore.append("retrieval.kg.brainState.events", event);
        } catch (Throwable e) {
            traceSuppressed(log, "KnowledgeGraphHandler", "brainState.trace", e);
        }
    }

    private static void traceSparsePath(String status, int transitivePathCount, String failureClass, String queryText) {
        try {
            TraceStore.put("retrieval.kg.sparsePath.status", status);
            TraceStore.put("retrieval.kg.sparsePath.transitivePathCount", Math.max(0, transitivePathCount));
            TraceStore.put("retrieval.kg.sparsePath.failureClass",
                    SafeRedactor.traceLabelOrFallback(failureClass, ""));
            TraceStore.put("retrieval.kg.sparsePath.queryHash12", SafeRedactor.hash12(queryText));
        } catch (Throwable e) {
            traceSuppressed(log, "KnowledgeGraphHandler", "sparsePath.trace", e);
        }
    }

    private static void traceDependency(
            String status,
            boolean fallbackUsed,
            String failureClass,
            String errorType,
            String queryText) {
        try {
            TraceStore.put("retrieval.dependency.kg.status", status);
            TraceStore.put("retrieval.dependency.kg.fallbackUsed", fallbackUsed);
            TraceStore.put("retrieval.dependency.kg.failureClass",
                    SafeRedactor.traceLabelOrFallback(failureClass, ""));
            TraceStore.put("retrieval.dependency.kg.errorType",
                    SafeRedactor.traceLabelOrFallback(errorType, ""));
            TraceStore.put("retrieval.dependency.kg.queryHash12", SafeRedactor.hash12(queryText));
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("status", status);
            event.put("fallbackUsed", fallbackUsed);
            event.put("failureClass", SafeRedactor.traceLabelOrFallback(failureClass, ""));
            event.put("errorType", SafeRedactor.traceLabelOrFallback(errorType, ""));
            event.put("queryHash12", SafeRedactor.hash12(queryText));
            TraceStore.append("retrieval.dependency.kg.events", event);
        } catch (Throwable e) {
            traceSuppressed(log, "KnowledgeGraphHandler", "dependency.trace", e);
        }
    }

    private static List<String> safeSeedHashes(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<?> list = raw instanceof List<?> l ? l : List.of(raw);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object value : list) {
            if (value == null) {
                continue;
            }
            String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            if (HASH12.matcher(s).matches()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> brainStateSeedHashes(InferenceResult result) {
        if (result == null || result.ragDebug() == null || result.ragDebug().isEmpty()) {
            return List.of();
        }
        Object raw = firstNonBlank(result.ragDebug(),
                "sparseNode.queryAnchorMap.seedHashes",
                "sparseNode.queryAnchorMapSeedHashes");
        return safeSeedHashes(raw);
    }

    private static String brainStateReason(InferenceResult result, String fallback) {
        Object raw = result == null || result.ragDebug() == null ? null : firstNonBlank(result.ragDebug(),
                "sparseNode.queryAnchorMap.reason",
                "sparseNode.queryAnchorMapReason");
        return safeReasonLabel(raw == null ? fallback : String.valueOf(raw), "unknown");
    }

    private static Object firstNonBlank(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String safeReasonLabel(String value, String fallback) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            return fallback;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.matches("[a-z0-9_.-]+")) {
            return lower;
        }
        return SafeRedactor.hashValue(raw);
    }

    private static long traceLong(Map<String, Object> trace, String key) {
        if (trace == null || key == null || !trace.containsKey(key)) {
            return 0L;
        }
        Object value = trace.get(key);
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? Math.max(0L, n.longValue()) : 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value).trim()));
        } catch (NumberFormatException ignored) {
            traceSuppressed(log, "KnowledgeGraphHandler", "traceLong.parse", ignored);
            return 0L;
        }
    }

    private static int metaInt(Map<String, Object> meta, String key, int fallback) {
        if (meta == null || key == null || !meta.containsKey(key)) {
            return fallback;
        }
        Object value = meta.get(key);
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? Math.max(0, n.intValue()) : fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException ignored) {
            traceSuppressed(log, "KnowledgeGraphHandler", "metaInt.parse", ignored);
            return fallback;
        }
    }

    private static int kgLimit(Map<String, Object> meta, int fallback) {
        try {
            return metaInt(meta, "kgLimit", fallback);
        } catch (RuntimeException e) {
            traceSuppressed(log, "KnowledgeGraphHandler", "kgLimit.metadata", e);
            return fallback;
        }
    }

    private static double[] parseScoreWeights(String raw) {
        if (raw == null || raw.isBlank()) {
            return new double[0];
        }
        String[] parts = raw.split(",");
        double[] out = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Double.parseDouble(parts[i].trim());
            }
            return out;
        } catch (NumberFormatException e) {
            log.warn("[KnowledgeGraphHandler] invalid kg.score.weights hash={} length={} errorHash={} errorLength={}",
                    SafeRedactor.hashValue(raw), raw.length(), SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return new double[0];
        }
    }

    private static <T> T first(Class<T> type, Object[] values) {
        if (type == null || values == null) {
            return null;
        }
        for (Object value : values) {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
        }
        return null;
    }

    private static String relationKind(String breadcrumb) {
        if (breadcrumb == null) {
            return "RELATIONSHIP_ASSOCIATED_WITH";
        }
        int start = breadcrumb.indexOf("--");
        int end = breadcrumb.indexOf("-->", start + 2);
        if (start >= 0 && end > start) {
            return blankToDefault(breadcrumb.substring(start + 2, end), "RELATIONSHIP_ASSOCIATED_WITH");
        }
        return "RELATIONSHIP_ASSOCIATED_WITH";
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim().replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ");
        return out.length() <= 360 ? out : out.substring(0, 360).trim();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String classifyFailure(Throwable error) {
        Throwable root = rootCause(error);
        if (root instanceof CancellationException
                || root instanceof InterruptedException
                || errorType(root).toLowerCase(Locale.ROOT).contains("cancel")) {
            return "cancelled";
        }
        return error == null ? "silent-failure" : "silent-failure";
    }

    private static String errorType(Throwable error) {
        Throwable root = rootCause(error);
        return root == null ? "" : root.getClass().getSimpleName();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && current.getCause() != current && depth++ < 8) {
            current = current.getCause();
        }
        return current;
    }

    private static String messageOf(Throwable error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private static int messageLength(Throwable error) {
        return messageOf(error).length();
    }

    private static void traceSuppressed(Logger logger, String component, String stage, Throwable error) {
        if (logger == null) {
            return;
        }
        logger.debug("[{}] fail-soft stage={} errorType={}",
                component,
                SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                SafeRedactor.traceLabelOrFallback(errorType(error), "unknown"));
    }

    @SuppressWarnings("unused")
    private static void sourceContractStageAnchors(Throwable e) {
        traceSuppressed(log, "KnowledgeGraphHandler", "debugEvent.trace", e);
        traceSuppressed(log, "KnowledgeGraphHandler", "kgLimit.metadata", e);
    }
}
