package com.example.lms.service.rag.graph;

import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class BrainStateService {

    /** Per-request private projection. No global entity maps, public Neo4j nodes or recursive RAG calls. */
    public record PrivateFact(String namespace,String sourceId,Set<String> entities){}
    public record PrivateExpansion(List<String> sources,int hops,int nodes,int edges){}
    public static PrivateExpansion expandPrivate(String namespace,List<PrivateFact> authorized,List<String> seeds,
                                                 java.util.function.BooleanSupplier current){
        if(namespace==null||!namespace.matches("[a-f0-9]{64}")||authorized.size()>256
            ||authorized.stream().anyMatch(f->!namespace.equals(f.namespace())))throw new IllegalArgumentException("private_graph_scope");
        var selected=new LinkedHashSet<String>(seeds);var frontier=new LinkedHashSet<String>();
        for(var f:authorized)if(selected.contains(f.sourceId()))for(var name:f.entities())if(frontier.size()<20)frontier.add(name);
        int edges=0,hops=0;
        for(int hop=0;hop<2&&edges<40;hop++){
            if(!current.getAsBoolean())throw new CancellationException("private_graph_cancelled");
            var next=new LinkedHashSet<String>();boolean changed=false;
            for(var f:authorized){
                if(selected.contains(f.sourceId())||java.util.Collections.disjoint(frontier,f.entities()))continue;
                if(selected.size()>=20||edges>=40)break;
                selected.add(f.sourceId());edges++;changed=true;
                for(var name:f.entities())if(frontier.size()+next.size()<20)next.add(name);
            }
            if(!changed)break;frontier.addAll(next);hops++;
        }
        return new PrivateExpansion(List.copyOf(selected),hops,frontier.size(),edges);
    }

    private static final Logger log = LoggerFactory.getLogger(BrainStateService.class);

    private final BrainStateProperties properties;
    private final Neo4jKgChunkWriter neo4jWriter;
    private final ObjectProvider<RagOrchestratorFacade> ragFacadeProvider;
    private final SparseNodeInferenceService sparseNodeInferenceService;
    private final AnchorFrequencyIndex anchorFrequencyIndex;
    private final QueryTimeAnchorMap queryTimeAnchorMap;
    private final ConcurrentMap<String, StoredChunk> chunks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, EntityAccumulator> entities = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RelationAccumulator> relations = new ConcurrentHashMap<>();
    private volatile BrainSnapshot.QueryTimeSummary latestQueryTime = BrainSnapshot.QueryTimeSummary.noRecent();
    private volatile Instant lastUpdatedAt;

    public BrainStateService(BrainStateProperties properties,
                             Neo4jKgChunkWriter neo4jWriter,
                             ObjectProvider<RagOrchestratorFacade> ragFacadeProvider) {
        this(properties, neo4jWriter, ragFacadeProvider, new SparseNodeInferenceService(),
                new AnchorFrequencyIndex(), null);
    }

    public BrainStateService(BrainStateProperties properties,
                             Neo4jKgChunkWriter neo4jWriter,
                             ObjectProvider<RagOrchestratorFacade> ragFacadeProvider,
                             SparseNodeInferenceService sparseNodeInferenceService) {
        this(properties, neo4jWriter, ragFacadeProvider, sparseNodeInferenceService,
                new AnchorFrequencyIndex(), null);
    }

    @Autowired
    public BrainStateService(BrainStateProperties properties,
                              Neo4jKgChunkWriter neo4jWriter,
                              ObjectProvider<RagOrchestratorFacade> ragFacadeProvider,
                              SparseNodeInferenceService sparseNodeInferenceService,
                              @Autowired(required = false) AnchorFrequencyIndex anchorFrequencyIndex,
                              @Autowired(required = false) QueryTimeAnchorMap queryTimeAnchorMap) {
        this.properties = properties;
        this.neo4jWriter = neo4jWriter;
        this.ragFacadeProvider = ragFacadeProvider;
        this.sparseNodeInferenceService = sparseNodeInferenceService == null
                ? new SparseNodeInferenceService()
                : sparseNodeInferenceService;
        this.anchorFrequencyIndex = anchorFrequencyIndex;
        this.queryTimeAnchorMap = queryTimeAnchorMap == null && anchorFrequencyIndex != null
                ? new QueryTimeAnchorMap(anchorFrequencyIndex)
                : queryTimeAnchorMap;
    }

    public void recordChunks(List<KgChunk> input) {
        if (!properties.isEnabled() || input == null || input.isEmpty()) {
            return;
        }
        recordAnchorFrequency(input);
        Instant now = Instant.now();
        for (KgChunk chunk : input) {
            if (chunk == null || chunk.chunkId() == null || chunk.chunkId().isBlank()) {
                continue;
            }
            StoredChunk stored = StoredChunk.from(chunk, now);
            chunks.put(stored.chunkId(), stored);
            for (KgChunk.KgEntity entity : chunk.entities()) {
                if (entity == null || entity.name() == null || entity.name().isBlank()) {
                    continue;
                }
                String domain = BrainStateText.normalizeDomain(entity.domain());
                String key = entityKey(domain, entity.name());
                entities.compute(key, (ignored, acc) -> {
                    EntityAccumulator next = acc == null
                            ? new EntityAccumulator(entity.name().trim(), entity.type(), domain)
                            : acc;
                    next.mentionCount++;
                    next.confidence = Math.max(next.confidence, clamp01(entity.confidence()));
                    next.sessionIds.add(stored.sessionId());
                    return next;
                });
            }
            for (KgChunk.KgRelation relation : chunk.relations()) {
                if (relation == null || relation.source() == null || relation.target() == null
                        || relation.source().isBlank() || relation.target().isBlank()) {
                    continue;
                }
                String domain = stored.domain();
                String key = relationKey(domain, relation);
                relations.compute(key, (ignored, acc) -> {
                    RelationAccumulator next = acc == null
                            ? new RelationAccumulator(domain, relation)
                            : acc;
                    next.count++;
                    next.confidence = Math.max(next.confidence, clamp01(relation.confidence()));
                    next.sessionIds.add(stored.sessionId());
                    return next;
                });
            }
        }
        lastUpdatedAt = now;
    }

    private void recordAnchorFrequency(List<KgChunk> input) {
        if (anchorFrequencyIndex == null || !anchorFrequencyIndex.isEnabled()) {
            return;
        }
        try {
            anchorFrequencyIndex.record(input);
        } catch (Exception ex) {
            traceSuppressed("recordAnchorFrequency", ex);
            // Anchor map is auxiliary; local brain-state recording must remain fail-soft.
        }
    }

    public BrainSnapshot getBrainSnapshot(String sessionId) {
        return getBrainSnapshot(sessionId, "", 20);
    }

    public BrainSnapshot getBrainSnapshot(String sessionId, String domain, int recentLimit) {
        String sid = normalizeSession(sessionId);
        String requestedDomain = normalizeOptionalDomain(domain);
        int safeRecentLimit = Math.max(1, Math.min(recentLimit <= 0 ? 20 : recentLimit, 100));
        List<StoredChunk> selectedChunks = chunks.values().stream()
                .filter(c -> sid.isBlank() || sid.equals(c.sessionId()))
                .filter(c -> domainMatches(requestedDomain, c.domain()))
                .toList();
        Set<String> selectedSessions = new LinkedHashSet<>();
        if (!sid.isBlank()) {
            selectedSessions.add(sid);
        } else {
            selectedChunks.forEach(c -> selectedSessions.add(c.sessionId()));
        }

        List<DomainSummary> summaries = domainSummaries(selectedChunks, selectedSessions, requestedDomain);
        List<String> topRelations = relations.values().stream()
                .filter(r -> includeBySessionAndDomain(r.sessionIds, selectedSessions, requestedDomain, r.domain))
                .sorted(Comparator.comparingLong((RelationAccumulator r) -> r.count).reversed())
                .limit(10)
                .map(RelationAccumulator::view)
                .toList();
        List<PortMappingView> topPortMappings = relations.values().stream()
                .filter(r -> includeBySessionAndDomain(r.sessionIds, selectedSessions, requestedDomain, r.domain))
                .sorted(Comparator.comparingLong((RelationAccumulator r) -> r.count).reversed()
                        .thenComparing(r -> r.connectorHash12))
                .limit(10)
                .map(RelationAccumulator::portMappingView)
                .toList();
        List<KgEntityView> sparseNodes = entities.values().stream()
                .filter(e -> includeBySessionAndDomain(e.sessionIds, selectedSessions, requestedDomain, e.domain))
                .filter(e -> e.mentionCount <= 1)
                .sorted(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)))
                .limit(25)
                .map(EntityAccumulator::view)
                .toList();
        List<BrainSnapshot.SourceSummary> sourceSummaries = sourceSummaries(selectedChunks);
        List<BrainSnapshot.RecentChange> recentChanges = recentChanges(selectedChunks, safeRecentLimit);
        BrainSnapshot.AnchorMapSummary anchorMap = anchorMapSummary(requestedDomain, safeRecentLimit);
        BrainSnapshot.QueryTimeSummary queryTime = queryTimeSummary();

        String disabled = neo4jWriter.disabledReason();
        return new BrainSnapshot(
                sid.isBlank() ? "ALL" : sid,
                selectedChunks.size(),
                entities.values().stream()
                        .filter(e -> includeBySessionAndDomain(e.sessionIds, selectedSessions, requestedDomain, e.domain))
                        .count(),
                summaries,
                topRelations,
                topPortMappings,
                sparseNodes,
                sourceSummaries,
                recentChanges,
                anchorMap,
                queryTime,
                Instant.now(),
                disabled == null ? "configured" : "disabled",
                disabled == null ? "" : disabled);
    }

    public List<DomainSummary> listKnownDomains() {
        return domainSummaries(List.copyOf(chunks.values()), Set.of(), "");
    }

    public List<KgEntityView> listEntityNodes(String domain, int limit) {
        String d = BrainStateText.normalizeDomain(domain);
        int safeLimit = Math.max(1, Math.min(limit, properties.getIndexing().getMaxEntitiesPerDomain()));
        return entities.values().stream()
                .filter(e -> d.equals(e.domain))
                .sorted(Comparator.comparingLong((EntityAccumulator e) -> e.mentionCount).reversed()
                        .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)))
                .limit(safeLimit)
                .map(EntityAccumulator::view)
                .toList();
    }

    public InferenceResult querySparseInference(String query) {
        return querySparseInferenceInternal(query, false, "", "");
    }

    public InferenceResult querySparseInference(String query, String domain) {
        return querySparseInferenceInternal(query, false, "", domain);
    }

    public InferenceResult querySparseInferenceLocalOnly(String query) {
        return querySparseInferenceLocalOnly(query, "");
    }

    public InferenceResult querySparseInferenceLocalOnly(String query, String domain) {
        return querySparseInferenceInternal(query, true, "kg_handler_fallback", domain);
    }

    private InferenceResult querySparseInferenceInternal(String query, boolean localOnly, String caller, String domain) {
        long started = System.nanoTime();
        String queryHash = BrainStateText.hash12(query);
        String requestedDomain = normalizeOptionalDomain(domain);
        if (!properties.isEnabled()) {
            BrainSnapshot.QueryTimeSummary disabledSummary = new BrainSnapshot.QueryTimeSummary(
                    "disabled",
                    false,
                    false,
                    0,
                    List.of(),
                    "",
                    0,
                    0,
                    0,
                    "brain_state_disabled",
                    "",
                    elapsedMs(started),
                    queryHash,
                    Instant.now());
            latestQueryTime = disabledSummary;
            return new InferenceResult(false, queryHash, "disabled", List.of(), List.of(), Map.of(),
                    "brain_state_disabled", disabledSummary.capturedAt());
        }
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<String> exactMatched = entities.values().stream()
                .filter(e -> domainMatches(requestedDomain, e.domain))
                .filter(e -> !lower.isBlank() && lower.contains(e.name.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingLong((EntityAccumulator e) -> e.mentionCount).reversed())
                .limit(10)
                .map(e -> e.name)
                .distinct()
                .toList();
        QueryTimeAnchorMap.AnchorSlice anchorSelection = queryAnchorSlice(query, exactMatched, requestedDomain);
        String inferenceDomain = requestedDomain.isBlank() && anchorSelection.applied()
                ? anchorSelection.domain()
                : requestedDomain;
        List<String> matched = anchorSelection.matchedEntities();
        List<SparseNodeInferenceService.MemoryPath> memoryPaths = inferLocalMemoryPaths(matched, inferenceDomain);
        List<String> inferred = memoryPaths.isEmpty()
                ? directRelationViews(matched, inferenceDomain)
                : memoryPaths.stream().map(SparseNodeInferenceService.MemoryPath::render).toList();

        Map<String, Object> ragDebug = new LinkedHashMap<>(localOnly
                ? Map.of("available", false)
                : runBoundedRag(query));
        ragDebug.put("sparseNode.mode", "associative_path_inference");
        ragDebug.put("sparseNode.localOnly", localOnly);
        ragDebug.put("sparseNode.domain", inferenceDomain.isBlank() ? "ALL" : inferenceDomain);
        if (caller != null && !caller.isBlank()) {
            ragDebug.put("sparseNode.caller", caller);
        }
        ragDebug.put("sparseNode.exactMatchedEntityCount", exactMatched.size());
        ragDebug.put("sparseNode.matchedEntityCount", matched.size());
        ragDebug.put("sparseNode.inferredRelationCount", inferred.size());
        ragDebug.put("sparseNode.pathCount", memoryPaths.size());
        ragDebug.put("sparseNode.transitivePathCount", memoryPaths.stream()
                .filter(SparseNodeInferenceService.MemoryPath::transitive)
                .count());
        ragDebug.put("sparseNode.queryAnchorMap.enabled", anchorSelection.enabled());
        ragDebug.put("sparseNode.queryAnchorMap.applied", anchorSelection.applied());
        ragDebug.put("sparseNode.queryAnchorMap.reason", anchorSelection.reason());
        ragDebug.put("sparseNode.queryAnchorMap.domain", anchorSelection.domain());
        ragDebug.put("sparseNode.queryAnchorMap.seedCount", anchorSelection.seedCount());
        ragDebug.put("sparseNode.queryAnchorMap.seedHashes", anchorSelection.seedHashes());
        ragDebug.put("sparseNode.queryAnchorMapSeedCount", anchorSelection.seedCount());
        ragDebug.put("sparseNode.queryAnchorMapSeedHashes", anchorSelection.seedHashes());
        ragDebug.put("sparseNode.portMappingCount", matchingPortMappingCount(matched, inferenceDomain));
        ragDebug.put("sparseNode.portMappingHashes", matchingPortMappingHashes(matched, inferenceDomain, 8));
        String disabled = inferred.isEmpty() ? "no_graph_path" : "";
        String mode = localOnly ? "local-brain-state-local-only-v1" : "local-brain-state-v1";
        BrainSnapshot.QueryTimeSummary summary = new BrainSnapshot.QueryTimeSummary(
                disabled.isBlank() ? "success" : disabled,
                !inferred.isEmpty(),
                anchorSelection.applied(),
                anchorSelection.seedCount(),
                anchorSelection.seedHashes(),
                anchorSelection.reason(),
                matched.size(),
                inferred.size(),
                matchingPortMappingCount(matched, inferenceDomain),
                disabled,
                "",
                elapsedMs(started),
                queryHash,
                Instant.now());
        latestQueryTime = summary;
        return new InferenceResult(true, queryHash, mode, matched, inferred, ragDebug,
                disabled, summary.capturedAt());
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("indexingEnabled", properties.getIndexing().isEnabled());
        out.put("captureChatWorkflow", properties.getIndexing().isCaptureChatWorkflow());
        out.put("chunkCount", chunks.size());
        out.put("entityCount", entities.size());
        out.put("relationCount", relations.size());
        out.put("lastUpdatedAt", lastUpdatedAt == null ? "" : lastUpdatedAt.toString());
        out.put("neo4j", neo4jWriter.status());
        return out;
    }

    private Map<String, Object> runBoundedRag(String query) {
        RagOrchestratorFacade facade = ragFacadeProvider == null ? null : ragFacadeProvider.getIfAvailable();
        if (facade == null || query == null || query.isBlank()) {
            return Map.of("available", false);
        }
        try {
            UnifiedRagOrchestrator.QueryRequest req = new UnifiedRagOrchestrator.QueryRequest();
            req.query = query;
            req.useWeb = false;
            req.useVector = true;
            req.useKg = true;
            req.useBm25 = false;
            req.enableSelfAsk = false;
            req.topK = 5;
            req.vectorTopK = 5;
            req.kgTopK = 5;
            req.planId = "safe_autorun.v1";
            UnifiedRagOrchestrator.QueryResponse response = facade.query(req);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", true);
            out.put("resultCount", response == null || response.results == null ? 0 : response.results.size());
            if (response != null && response.debug != null) {
                copyIfPresent(response.debug, out, "langgraph.mode");
                copyIfPresent(response.debug, out, "plan.vectorTopK");
                copyIfPresent(response.debug, out, "plan.kgTopK");
                copyIfPresent(response.debug, out, "retrieval.kg.neo4j.status");
                copyIfPresent(response.debug, out, "retrieval.kg.neo4j.disabledReason");
                copyIfPresent(response.debug, out, "retrieval.kg.neo4j.failureClass");
                copyIfPresent(response.debug, out, "rag.eval.kgAxis.neo4jDisabledReason");
                copyIfPresent(response.debug, out, "rag.eval.kgAxis.neo4jFailureClass");
                copyKgAxisNeo4jIfPresent(response.debug, out);
            }
            return out;
        } catch (Exception ex) {
            traceSuppressed("langgraph.status", ex);
            return Map.of("available", true, "failureClass", failureClass(ex));
        }
    }

    private static String failureClass(Throwable failure) {
        Throwable root = rootCause(failure);
        String className = root == null ? "" : root.getClass().getSimpleName();
        String lowerClass = className.toLowerCase(Locale.ROOT);
        String message = root == null || root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        if (root instanceof CancellationException
                || root instanceof InterruptedException
                || lowerClass.contains("cancel")
                || lowerClass.contains("interrupt")
                || message.contains("cancelled")
                || message.contains("canceled")
                || message.contains("interrupted")) {
            return "cancelled";
        }
        return SafeRedactor.traceLabelOrFallback(className, "unknown");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && current.getCause() != current && depth++ < 8) {
            current = current.getCause();
        }
        return current;
    }

    private List<SparseNodeInferenceService.MemoryPath> inferLocalMemoryPaths(List<String> matched, String domain) {
        if (matched == null || matched.isEmpty() || sparseNodeInferenceService == null) {
            return List.of();
        }
        return sparseNodeInferenceService.infer(
                new LinkedHashSet<>(matched),
                entity -> localRelationships(entity, domain),
                entity -> localConfidence(entity, domain),
                10);
    }

    private QueryTimeAnchorMap.AnchorSlice queryAnchorSlice(String query, List<String> exactMatched, String domain) {
        String requestedDomain = AnchorFrequencyIndex.normalizeOptionalDomain(domain);
        List<String> exact = exactMatched == null ? List.of() : List.copyOf(exactMatched);
        if (queryTimeAnchorMap == null) {
            if (!exact.isEmpty()) {
                return new QueryTimeAnchorMap.AnchorSlice(false, false, exact, List.of(), List.of(),
                        "exact_entity_match", requestedDomain, "");
            }
            return new QueryTimeAnchorMap.AnchorSlice(false, false, List.of(), List.of(), List.of(),
                    "index_unavailable", requestedDomain, "index_unavailable");
        }
        try {
            return queryTimeAnchorMap.slice(query, requestedDomain, exact);
        } catch (Exception ex) {
            traceSuppressed("anchorSlice", ex);
            if (!exact.isEmpty()) {
                return new QueryTimeAnchorMap.AnchorSlice(false, false, exact, List.of(), List.of(),
                        "exact_entity_match", requestedDomain, "");
            }
            return new QueryTimeAnchorMap.AnchorSlice(false, false, List.of(), List.of(), List.of(),
                    "anchor_map_failed", requestedDomain, "anchor_map_failed");
        }
    }

    private List<String> directRelationViews(List<String> matched, String domain) {
        if (matched == null || matched.isEmpty()) {
            return List.of();
        }
        return relations.values().stream()
                .filter(r -> domainMatches(domain, r.domain))
                .filter(r -> matched.stream().anyMatch(m -> equalsName(m, r.source) || equalsName(m, r.target)))
                .sorted(Comparator.comparingLong((RelationAccumulator r) -> r.count).reversed())
                .limit(10)
                .map(RelationAccumulator::view)
                .toList();
    }

    private long matchingPortMappingCount(List<String> matched, String domain) {
        if (matched == null || matched.isEmpty()) {
            return 0L;
        }
        return relations.values().stream()
                .filter(r -> matchesAny(matched, r, domain))
                .filter(r -> r.connectorHash12 != null && !r.connectorHash12.isBlank())
                .count();
    }

    private List<String> matchingPortMappingHashes(List<String> matched, String domain, int limit) {
        if (matched == null || matched.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 25));
        return relations.values().stream()
                .filter(r -> matchesAny(matched, r, domain))
                .map(r -> r.connectorHash12)
                .filter(hash -> hash != null && !hash.isBlank())
                .distinct()
                .sorted()
                .limit(safeLimit)
                .toList();
    }

    private static boolean matchesAny(List<String> matched, RelationAccumulator relation, String domain) {
        if (relation == null) {
            return false;
        }
        if (!domainMatches(domain, relation.domain)) {
            return false;
        }
        return matched != null && !matched.isEmpty()
                && matched.stream().anyMatch(m -> equalsName(m, relation.source) || equalsName(m, relation.target));
    }

    private Map<String, Set<String>> localRelationships(String entity, String domain) {
        if (entity == null || entity.isBlank()) {
            return Map.of();
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (RelationAccumulator relation : relations.values()) {
            if (!domainMatches(domain, relation.domain)) {
                continue;
            }
            if (equalsName(entity, relation.source)) {
                out.computeIfAbsent(relation.kind, ignored -> new LinkedHashSet<>()).add(relation.target);
            }
        }
        return out;
    }

    private double localConfidence(String entity, String domain) {
        if (entity == null || entity.isBlank()) {
            return 1.0d;
        }
        return entities.values().stream()
                .filter(e -> domainMatches(domain, e.domain))
                .filter(e -> equalsName(entity, e.name))
                .mapToDouble(e -> e.confidence)
                .max()
                .orElse(1.0d);
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            Object value = source.get(key);
            target.put(key, value == null ? "" : value);
        }
    }

    private static void copyKgAxisNeo4jIfPresent(Map<String, Object> source, Map<String, Object> target) {
        Object raw = source.get("rag.eval.kgAxis");
        if (!(raw instanceof Map<?, ?> kgAxis)) {
            return;
        }
        copyNestedIfPresent(kgAxis, target, "neo4jDisabledReason", "rag.eval.kgAxis.neo4jDisabledReason");
        copyNestedIfPresent(kgAxis, target, "neo4jFailureClass", "rag.eval.kgAxis.neo4jFailureClass");
    }

    private static void copyNestedIfPresent(Map<?, ?> source, Map<String, Object> target, String sourceKey, String targetKey) {
        if (source.containsKey(sourceKey)) {
            Object value = source.get(sourceKey);
            target.putIfAbsent(targetKey, value == null ? "" : value);
        }
    }

    private List<DomainSummary> domainSummaries(List<StoredChunk> selectedChunks,
                                                Set<String> selectedSessions,
                                                String requestedDomain) {
        Map<String, long[]> byDomain = new LinkedHashMap<>();
        for (StoredChunk chunk : selectedChunks) {
            byDomain.computeIfAbsent(chunk.domain(), ignored -> new long[3])[0]++;
        }
        for (EntityAccumulator entity : entities.values()) {
            if (includeBySessionAndDomain(entity.sessionIds, selectedSessions, requestedDomain, entity.domain)) {
                byDomain.computeIfAbsent(entity.domain, ignored -> new long[3])[1]++;
            }
        }
        for (RelationAccumulator relation : relations.values()) {
            if (includeBySessionAndDomain(relation.sessionIds, selectedSessions, requestedDomain, relation.domain)) {
                byDomain.computeIfAbsent(relation.domain, ignored -> new long[3])[2]++;
            }
        }
        return byDomain.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new DomainSummary(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }

    private List<BrainSnapshot.SourceSummary> sourceSummaries(List<StoredChunk> selectedChunks) {
        Map<String, SourceAccumulator> bySource = new LinkedHashMap<>();
        for (StoredChunk chunk : selectedChunks) {
            String key = String.join("|", chunk.sourceTag(), chunk.origin(), chunk.docType(), chunk.lane());
            bySource.computeIfAbsent(key, ignored -> new SourceAccumulator(
                    chunk.sourceTag(), chunk.origin(), chunk.docType(), chunk.lane()))
                    .record(chunk);
        }
        return bySource.values().stream()
                .sorted(Comparator.comparingLong(SourceAccumulator::chunkCount).reversed()
                        .thenComparing(SourceAccumulator::key))
                .limit(25)
                .map(SourceAccumulator::view)
                .toList();
    }

    private List<BrainSnapshot.RecentChange> recentChanges(List<StoredChunk> selectedChunks, int limit) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        return selectedChunks.stream()
                .sorted(Comparator.comparing(StoredChunk::capturedAt).reversed()
                        .thenComparing(StoredChunk::chunkHash12))
                .limit(safeLimit)
                .map(chunk -> new BrainSnapshot.RecentChange(
                        chunk.chunkHash12(),
                        chunk.sessionHash12(),
                        chunk.domain(),
                        chunk.sourceTag(),
                        chunk.origin(),
                        chunk.docType(),
                        chunk.lane(),
                        chunk.textLength(),
                        chunk.entityCount(),
                        chunk.relationCount(),
                        chunk.capturedAt()))
                .toList();
    }

    private BrainSnapshot.AnchorMapSummary anchorMapSummary(String domain, int limit) {
        if (anchorFrequencyIndex == null) {
            String summaryDomain = domain == null || domain.isBlank() ? "ALL" : domain;
            return BrainSnapshot.AnchorMapSummary.disabled(summaryDomain, "index_unavailable");
        }
        try {
            return anchorFrequencyIndex.summary(domain, limit);
        } catch (Exception ex) {
            traceSuppressed("anchorMap.summary", ex);
            String summaryDomain = domain == null || domain.isBlank() ? "ALL" : domain;
            return BrainSnapshot.AnchorMapSummary.disabled(summaryDomain, "summary_failed");
        }
    }

    private BrainSnapshot.QueryTimeSummary queryTimeSummary() {
        BrainSnapshot.QueryTimeSummary inMemory = latestQueryTime;
        if (inMemory != null && !"no_recent_query".equals(inMemory.status())) {
            return inMemory;
        }
        BrainSnapshot.QueryTimeSummary traceSummary = queryTimeFromTrace();
        return traceSummary == null ? BrainSnapshot.QueryTimeSummary.noRecent() : traceSummary;
    }

    private BrainSnapshot.QueryTimeSummary queryTimeFromTrace() {
        Map<String, Object> trace = TraceStore.getAll();
        if (trace == null || trace.isEmpty()) {
            return null;
        }
        String status = traceString(trace, "retrieval.kg.brainState.status");
        String reason = traceString(trace, "retrieval.kg.brainState.reason");
        String disabledReason = traceString(trace, "retrieval.kg.brainState.disabledReason");
        String failureClass = traceString(trace, "retrieval.kg.brainState.failureClass");
        boolean fallbackUsed = traceBoolean(trace, "retrieval.kg.brainState.fallbackUsed");
        boolean queryAnchorMapApplied = traceBoolean(trace, "retrieval.kg.brainState.queryAnchorMap.applied");
        long queryAnchorMapSeedCount = traceLong(trace, "retrieval.kg.brainState.queryAnchorMap.seedCount");
        List<String> queryAnchorMapSeedHashes = traceHashList(trace, "retrieval.kg.brainState.queryAnchorMap.seedHashes");
        String queryAnchorMapReason = traceString(trace, "retrieval.kg.brainState.queryAnchorMap.reason");
        long matchedEntityCount = traceLong(trace, "retrieval.kg.brainState.matchedEntityCount");
        long inferredRelationCount = traceLong(trace, "retrieval.kg.brainState.inferredRelationCount");
        long portMappingCount = traceLong(trace, "retrieval.kg.brainState.portMappingCount");
        long tookMs = traceLong(trace, "retrieval.kg.brainState.tookMs");
        String queryHash12 = traceString(trace, "retrieval.kg.brainState.queryHash12");
        String normalizedStatus = firstNonBlank(status, reason, disabledReason.isBlank() ? "" : "disabled");
        if (normalizedStatus.isBlank()) {
            String sparseNodeStatus = traceString(trace, "rag.eval.kgAxis.sparseNodeStatus");
            String sparseNodeDisabledReason = traceString(trace, "rag.eval.kgAxis.sparseNodeDisabledReason");
            String sparseNodeFailureClass = traceString(trace, "rag.eval.kgAxis.sparseNodeFailureClass");
            String neo4jDisabledReason = traceString(trace, "rag.eval.kgAxis.neo4jDisabledReason");
            String neo4jFailureClass = traceString(trace, "rag.eval.kgAxis.neo4jFailureClass");
            normalizedStatus = firstNonBlank(
                    sparseNodeStatus,
                    sparseNodeDisabledReason,
                    neo4jDisabledReason.isBlank() && neo4jFailureClass.isBlank() ? "" : "kg_axis_degraded");
            disabledReason = firstNonBlank(sparseNodeDisabledReason, neo4jDisabledReason);
            failureClass = firstNonBlank(sparseNodeFailureClass, neo4jFailureClass);
            fallbackUsed = traceBoolean(trace, "rag.eval.kgAxis.sparseNodeQueryAnchorMapApplied")
                    || !sparseNodeDisabledReason.isBlank()
                    || !sparseNodeFailureClass.isBlank();
            queryAnchorMapApplied = traceBoolean(trace, "rag.eval.kgAxis.sparseNodeQueryAnchorMapApplied");
            queryAnchorMapSeedCount = traceLong(trace, "rag.eval.kgAxis.sparseNodeQueryAnchorMapSeedCount");
            queryAnchorMapSeedHashes = traceHashList(trace, "rag.eval.kgAxis.sparseNodeQueryAnchorMapSeedHashes");
            queryAnchorMapReason = traceString(trace, "rag.eval.kgAxis.sparseNodeQueryAnchorMapReason");
            portMappingCount = traceLong(trace, "rag.eval.kgAxis.sparseNodePortMappingCount");
        }
        if (normalizedStatus.isBlank()) {
            return null;
        }
        return new BrainSnapshot.QueryTimeSummary(
                normalizedStatus,
                fallbackUsed,
                queryAnchorMapApplied,
                queryAnchorMapSeedCount,
                queryAnchorMapSeedHashes,
                queryAnchorMapReason,
                matchedEntityCount,
                inferredRelationCount,
                portMappingCount,
                disabledReason,
                failureClass,
                tookMs,
                queryHash12,
                Instant.now());
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static boolean includeBySessionAndDomain(Set<String> candidateSessions,
                                                     Set<String> selectedSessions,
                                                     String requestedDomain,
                                                     String actualDomain) {
        if (!domainMatches(requestedDomain, actualDomain)) {
            return false;
        }
        return selectedSessions == null || selectedSessions.isEmpty()
                || intersects(candidateSessions, selectedSessions);
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String traceString(Map<String, Object> trace, String key) {
        Object value = trace == null ? null : trace.get(key);
        if (value == null) {
            return "";
        }
        return SafeRedactor.traceLabelOrFallback(value, "");
    }

    private static boolean traceBoolean(Map<String, Object> trace, String key) {
        Object value = trace == null ? null : trace.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static long traceLong(Map<String, Object> trace, String key) {
        Object value = trace == null ? null : trace.get(key);
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric)) {
                return Math.max(0L, number.longValue());
            }
            traceSuppressed("traceLong", new NumberFormatException("non_finite"));
            return 0L;
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value).trim()));
        } catch (NumberFormatException ex) {
            traceSuppressed("traceLong", ex);
            return 0L;
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeError = suppressedErrorType(failure);
        TraceStore.put("retrieval.kg.brainState.suppressed." + safeStage, true);
        TraceStore.put("retrieval.kg.brainState.suppressed." + safeStage + ".errorType", safeError);
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("[BrainStateService] fail-soft stage={} err={}", safeStage, safeError);
    }

    private static String suppressedErrorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static List<String> traceHashList(Map<String, Object> trace, String key) {
        Object value = trace == null ? null : trace.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(BrainStateService::safeHash12)
                    .filter(hash -> !hash.isBlank())
                    .distinct()
                    .toList();
        }
        String hash = safeHash12(value);
        return hash.isBlank() ? List.of() : List.of(hash);
    }

    private static String safeHash12(Object value) {
        if (value == null) {
            return "";
        }
        String hex = String.valueOf(value).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
        return hex.length() < 12 ? "" : hex.substring(0, 12);
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            return false;
        }
        for (String value : left) {
            if (right.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsName(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String normalizeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || "ALL".equalsIgnoreCase(sessionId)) {
            return "";
        }
        return sessionId.trim();
    }

    private static String normalizeOptionalDomain(String domain) {
        return domain == null || domain.isBlank() ? "" : BrainStateText.normalizeDomain(domain);
    }

    private static boolean domainMatches(String requestedDomain, String actualDomain) {
        return requestedDomain == null || requestedDomain.isBlank()
                || requestedDomain.equals(BrainStateText.normalizeDomain(actualDomain));
    }

    private static String entityKey(String domain, String name) {
        return domain + "|" + name.trim().toLowerCase(Locale.ROOT);
    }

    private static String relationKey(String domain, KgChunk.KgRelation relation) {
        return domain + "|" + relation.source().trim().toLowerCase(Locale.ROOT) + "|"
                + relation.target().trim().toLowerCase(Locale.ROOT) + "|"
                + BrainStateText.nonBlank(relation.kind(), "RELATED_TO").toUpperCase(Locale.ROOT) + "|"
                + GraphRagPortMappingConnector.safePort(
                relation.sourcePort(),
                GraphRagPortMappingConnector.DEFAULT_SOURCE_PORT) + "|"
                + GraphRagPortMappingConnector.safePort(
                relation.targetPort(),
                GraphRagPortMappingConnector.DEFAULT_TARGET_PORT) + "|"
                + GraphRagPortMappingConnector.safeHash12(
                relation.connectorHash12(),
                relation.source(),
                relation.sourcePort(),
                relation.target(),
                relation.targetPort(),
                relation.connectorKind());
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private record StoredChunk(
            String chunkId,
            String sessionId,
            String domain,
            String chunkHash12,
            String sessionHash12,
            String sourceTag,
            String docType,
            String origin,
            String lane,
            int textLength,
            int entityCount,
            int relationCount,
            Instant capturedAt) {

        static StoredChunk from(KgChunk chunk, Instant capturedAt) {
            String sessionId = BrainStateText.nonBlank(chunk.sessionId(), "__TRANSIENT__");
            return new StoredChunk(
                    chunk.chunkId(),
                    sessionId,
                    BrainStateText.normalizeDomain(chunk.domain()),
                    BrainStateText.hash12(chunk.chunkId()),
                    BrainStateText.hash12(sessionId),
                    BrainStateText.nonBlank(chunk.sourceTag(), "UNKNOWN"),
                    BrainStateText.nonBlank(chunk.docType(), "UNKNOWN"),
                    BrainStateText.nonBlank(chunk.origin(), "UNKNOWN"),
                    BrainStateText.nonBlank(chunk.ingestLane(), "UNKNOWN"),
                    chunk.sourceText() == null ? 0 : chunk.sourceText().length(),
                    chunk.entities() == null ? 0 : chunk.entities().size(),
                    chunk.relations() == null ? 0 : chunk.relations().size(),
                    capturedAt == null ? Instant.now() : capturedAt);
        }
    }

    private static final class SourceAccumulator {
        private final String sourceTag;
        private final String origin;
        private final String docType;
        private final String lane;
        private long chunkCount;
        private long entityCount;
        private long relationCount;
        private Instant lastUpdatedAt = Instant.EPOCH;

        private SourceAccumulator(String sourceTag, String origin, String docType, String lane) {
            this.sourceTag = sourceTag;
            this.origin = origin;
            this.docType = docType;
            this.lane = lane;
        }

        private void record(StoredChunk chunk) {
            chunkCount++;
            entityCount += chunk.entityCount();
            relationCount += chunk.relationCount();
            if (chunk.capturedAt().isAfter(lastUpdatedAt)) {
                lastUpdatedAt = chunk.capturedAt();
            }
        }

        private long chunkCount() {
            return chunkCount;
        }

        private String key() {
            return String.join("|", sourceTag, origin, docType, lane);
        }

        private BrainSnapshot.SourceSummary view() {
            return new BrainSnapshot.SourceSummary(
                    sourceTag,
                    origin,
                    docType,
                    lane,
                    chunkCount,
                    entityCount,
                    relationCount,
                    lastUpdatedAt);
        }
    }

    private static final class EntityAccumulator {
        private final String name;
        private final String type;
        private final String domain;
        private final Set<String> sessionIds = ConcurrentHashMap.newKeySet();
        private long mentionCount;
        private double confidence;

        private EntityAccumulator(String name, String type, String domain) {
            this.name = name;
            this.type = BrainStateText.nonBlank(type, "ENTITY");
            this.domain = domain;
        }

        private KgEntityView view() {
            return new KgEntityView(name, type, domain, mentionCount, confidence);
        }
    }

    private static final class RelationAccumulator {
        private final String domain;
        private final String source;
        private final String target;
        private final String kind;
        private final String sourcePort;
        private final String targetPort;
        private final String connectorKind;
        private final String connectorHash12;
        private final Set<String> sessionIds = ConcurrentHashMap.newKeySet();
        private long count;
        private double confidence;

        private RelationAccumulator(String domain, KgChunk.KgRelation relation) {
            this.domain = BrainStateText.normalizeDomain(domain);
            this.source = relation.source().trim();
            this.target = relation.target().trim();
            this.kind = BrainStateText.nonBlank(relation.kind(), "RELATED_TO");
            this.sourcePort = GraphRagPortMappingConnector.safePort(
                    relation.sourcePort(),
                    GraphRagPortMappingConnector.DEFAULT_SOURCE_PORT);
            this.targetPort = GraphRagPortMappingConnector.safePort(
                    relation.targetPort(),
                    GraphRagPortMappingConnector.DEFAULT_TARGET_PORT);
            this.connectorKind = GraphRagPortMappingConnector.safeKind(relation.connectorKind(), this.kind);
            this.connectorHash12 = GraphRagPortMappingConnector.safeHash12(
                    relation.connectorHash12(),
                    relation.source(),
                    relation.sourcePort(),
                    relation.target(),
                    relation.targetPort(),
                    relation.connectorKind());
        }

        private String view() {
            return source + " [" + sourcePort + "] -" + kind + "-> [" + targetPort + "] "
                    + target + " (" + count + ", connector=" + connectorHash12 + ")";
        }

        private PortMappingView portMappingView() {
            return new PortMappingView(
                    connectorHash12,
                    BrainStateText.hash12(source),
                    sourcePort,
                    BrainStateText.hash12(target),
                    targetPort,
                    kind,
                    connectorKind,
                    count);
        }
    }
}
