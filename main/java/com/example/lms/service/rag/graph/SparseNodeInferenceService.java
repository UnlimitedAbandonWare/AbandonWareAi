package com.example.lms.service.rag.graph;

import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SparseNodeInferenceService {

    private static final Logger log = LoggerFactory.getLogger(SparseNodeInferenceService.class);

    private static final int MAX_FRONTIER = 64;

    @Value("${retrieval.kg.sparse-path.max-depth:2}")
    private int configuredMaxDepth = 2;

    @Value("${retrieval.kg.sparse-path.max-paths:8}")
    private int configuredMaxPaths = 8;

    public List<MemoryPath> infer(Set<String> seedEntities,
                                  RelationProvider relationProvider,
                                  ConfidenceProvider confidenceProvider,
                                  int requestedLimit) {
        int depth = clamp(configuredMaxDepth, 1, 3);
        int limit = requestedLimit > 0 ? requestedLimit : configuredMaxPaths;
        return infer(seedEntities, relationProvider, confidenceProvider, depth, limit);
    }

    public List<MemoryPath> infer(Set<String> seedEntities,
                                  RelationProvider relationProvider,
                                  ConfidenceProvider confidenceProvider,
                                  int requestedDepth,
                                  int requestedLimit) {
        if (seedEntities == null || seedEntities.isEmpty() || relationProvider == null) {
            return List.of();
        }
        int maxDepth = clamp(requestedDepth, 1, 3);
        int limit = clamp(requestedLimit, 1, 50);
        List<String> seeds = normalizeSeeds(seedEntities);
        if (seeds.isEmpty()) {
            return List.of();
        }
        Set<String> seedKeys = new LinkedHashSet<>();
        seeds.forEach(seed -> seedKeys.add(key(seed)));

        Deque<FrontierPath> queue = new ArrayDeque<>();
        for (String seed : seeds) {
            queue.add(new FrontierPath(seed, List.of(seed), List.of(), 1.0d));
        }

        Map<String, MemoryPath> bestBySignature = new LinkedHashMap<>();
        int expanded = 0;
        while (!queue.isEmpty() && expanded < MAX_FRONTIER) {
            FrontierPath current = queue.removeFirst();
            expanded++;
            if (current.depth() >= maxDepth) {
                continue;
            }
            Map<String, Set<String>> relations = safeRelations(relationProvider, current.last());
            if (relations.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Set<String>> entry : sortedRelations(relations).entrySet()) {
                String kind = normalizeKind(entry.getKey());
                for (String target : sortedTargets(entry.getValue())) {
                    if (containsEntity(current.entities(), target)) {
                        continue;
                    }
                    List<String> nextEntities = append(current.entities(), target);
                    List<String> nextKinds = append(current.relationKinds(), kind);
                    int nextDepth = nextKinds.size();
                    double targetConfidence = confidence(confidenceProvider, target);
                    double avgConfidence = ((current.avgConfidence() * current.depth()) + targetConfidence)
                            / Math.max(1, nextDepth);
                    double novelty = seedKeys.contains(key(target)) ? 0.0d : 1.0d;
                    double score = score(nextDepth, avgConfidence, novelty);
                    MemoryPath candidate = new MemoryPath(current.seed(), nextEntities, nextKinds, score);
                    bestBySignature.merge(candidate.signature(), candidate,
                            (left, right) -> right.score() > left.score() ? right : left);
                    if (nextDepth < maxDepth && queue.size() < MAX_FRONTIER) {
                        queue.addLast(new FrontierPath(current.seed(), nextEntities, nextKinds, avgConfidence));
                    }
                }
            }
        }
        return bestBySignature.values().stream()
                .sorted(Comparator.comparingDouble(MemoryPath::score).reversed()
                        .thenComparingInt(MemoryPath::depth)
                        .thenComparing(MemoryPath::render))
                .limit(limit)
                .toList();
    }

    private static List<String> normalizeSeeds(Set<String> seedEntities) {
        return seedEntities.stream()
                .filter(SparseNodeInferenceService::hasText)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static Map<String, Set<String>> safeRelations(RelationProvider provider, String entity) {
        try {
            Map<String, Set<String>> relations = provider.relationships(entity);
            return relations == null ? Map.of() : relations;
        } catch (Exception ex) {
            traceSuppressed("relations", ex);
            return Map.of();
        }
    }

    private static Map<String, Set<String>> sortedRelations(Map<String, Set<String>> relations) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        relations.entrySet().stream()
                .filter(e -> hasText(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static List<String> sortedTargets(Set<String> targets) {
        return targets.stream()
                .filter(SparseNodeInferenceService::hasText)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static double confidence(ConfidenceProvider provider, String entity) {
        if (provider == null) {
            return 1.0d;
        }
        try {
            return clamp01(provider.confidence(entity));
        } catch (Exception ex) {
            traceSuppressed("confidence", ex);
            return 1.0d;
        }
    }

    private static void traceSuppressed(String stage, Exception ex) {
        String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String error = ex == null
                ? "unknown"
                : com.example.lms.trace.SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
        TraceStore.put("retrieval.kg.sparseNode.suppressed." + safeStage, true);
        TraceStore.put("retrieval.kg.sparseNode." + safeStage + ".errorType", error);
        if (log.isDebugEnabled()) {
            log.debug("[SparseNodeInferenceService] fail-soft stage={} err={}", safeStage, error);
        }
    }

    private static double score(int depth, double confidence, double novelty) {
        double depthTerm = 1.0d / (1.0d + Math.max(1, depth));
        return clamp01((0.55d * clamp01(confidence)) + (0.25d * depthTerm) + (0.20d * clamp01(novelty)));
    }

    private static boolean containsEntity(List<String> entities, String target) {
        String targetKey = key(target);
        return entities.stream().anyMatch(entity -> key(entity).equals(targetKey));
    }

    private static String normalizeKind(String kind) {
        if (!hasText(kind)) {
            return "RELATED_TO";
        }
        String out = kind.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-]+", "_");
        out = out.replaceAll("_+", "_").replaceAll("^_|_$", "");
        return out.isBlank() ? "RELATED_TO" : out;
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static <T> List<T> append(List<T> source, T value) {
        List<T> out = new ArrayList<>(source.size() + 1);
        out.addAll(source);
        out.add(value);
        return List.copyOf(out);
    }

    @FunctionalInterface
    public interface RelationProvider {
        Map<String, Set<String>> relationships(String entity);
    }

    @FunctionalInterface
    public interface ConfidenceProvider {
        double confidence(String entity);
    }

    public record MemoryPath(String seed,
                             List<String> entities,
                             List<String> relationKinds,
                             double score) {

        public MemoryPath {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relationKinds = relationKinds == null ? List.of() : List.copyOf(relationKinds);
            score = clamp01(score);
        }

        public int depth() {
            return relationKinds.size();
        }

        public boolean transitive() {
            return depth() > 1;
        }

        public String terminalEntity() {
            return entities.isEmpty() ? "" : entities.get(entities.size() - 1);
        }

        public String render() {
            if (entities.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder(128);
            out.append(entities.get(0));
            for (int i = 0; i < relationKinds.size(); i++) {
                String target = i + 1 < entities.size() ? entities.get(i + 1) : "";
                out.append(" --").append(relationKinds.get(i)).append("--> ").append(target);
            }
            return out.toString();
        }

        private String signature() {
            return render().toLowerCase(Locale.ROOT);
        }
    }

    private record FrontierPath(String seed,
                                List<String> entities,
                                List<String> relationKinds,
                                double avgConfidence) {
        int depth() {
            return relationKinds.size();
        }

        String last() {
            return entities.get(entities.size() - 1);
        }
    }
}
