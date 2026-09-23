package com.example.lms.service.rag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LogicRagPlanner {

    private static final Logger log = LoggerFactory.getLogger(LogicRagPlanner.class);

    private final boolean forceCycleForTests;

    LogicRagPlanner() {
        this(false);
    }

    LogicRagPlanner(boolean forceCycleForTests) {
        this.forceCycleForTests = forceCycleForTests;
    }

    Result plan(String query, List<Seed> seeds, int maxNodes, boolean pruneDuplicates) {
        List<Seed> safeSeeds = safeSeeds(seeds);
        try {
            DependencyMode mode = inferMode(query);
            NodeSelection selection = nodes(safeSeeds, Math.max(1, maxNodes), pruneDuplicates);
            List<Node> nodes = selection.nodes();
            Graph graph = graph(nodes, mode);
            List<Node> ordered = topologicalSort(nodes, graph.edges());
            List<Seed> planned = ordered.stream().map(Node::seed).toList();
            return new Result(planned, trace(true, mode.id, planned, graph.edgeCount(),
                    selection.prunedDuplicateCount(), "none"));
        } catch (Exception e) {
            log.debug("[LogicRagPlanner] fail-soft stage={}", "plan");
            return new Result(safeSeeds, trace(true, "default", safeSeeds, 0, 0, failureClass(e)));
        }
    }

    private NodeSelection nodes(List<Seed> seeds, int maxNodes, boolean pruneDuplicates) {
        LinkedHashMap<String, Node> deduped = new LinkedHashMap<>();
        ArrayList<Node> out = new ArrayList<>();
        int index = 0;
        int prunedDuplicateCount = 0;
        for (Seed seed : seeds) {
            if (seed == null || isBlank(seed.query())) {
                continue;
            }
            Node node = new Node(index++, seed);
            if (pruneDuplicates) {
                String key = canonical(seed.query());
                if (deduped.containsKey(key)) {
                    prunedDuplicateCount++;
                } else {
                    deduped.put(key, node);
                }
            } else {
                out.add(node);
            }
        }
        if (pruneDuplicates) {
            out.addAll(deduped.values());
        }
        List<Node> selected = out.stream()
                .sorted(Comparator.comparingInt(Node::index))
                .limit(Math.max(1, maxNodes))
                .toList();
        return new NodeSelection(selected, prunedDuplicateCount);
    }

    private Graph graph(List<Node> nodes, DependencyMode mode) {
        Map<Integer, Set<Integer>> edges = new LinkedHashMap<>();
        for (Node node : nodes) {
            edges.put(node.index(), new LinkedHashSet<>());
        }
        switch (mode) {
            case ENTITY_FIRST -> {
                addLaneEdges(nodes, edges, "ER", "BQ");
                addLaneEdges(nodes, edges, "ER", "RC");
                addLaneEdges(nodes, edges, "BQ", "RC");
            }
            case RECENCY_FIRST -> {
                addLaneEdges(nodes, edges, "BQ", "RC");
                addLaneEdges(nodes, edges, "RC", "ER");
            }
            case CONTRAST_REASONING -> {
                addLaneEdges(nodes, edges, "BQ", "ER");
                addLaneEdges(nodes, edges, "ER", "RC");
            }
            case DEFAULT -> {
                // Preserve existing Self-Ask order.
            }
        }
        if (forceCycleForTests && nodes.size() > 1) {
            int first = nodes.get(0).index();
            int last = nodes.get(nodes.size() - 1).index();
            edges.get(first).add(last);
            edges.get(last).add(first);
        }
        int edgeCount = edges.values().stream().mapToInt(Set::size).sum();
        return new Graph(edges, edgeCount);
    }

    private static void addLaneEdges(List<Node> nodes, Map<Integer, Set<Integer>> edges, String before, String after) {
        List<Node> beforeNodes = nodes.stream().filter(n -> before.equals(n.seed().lane())).toList();
        List<Node> afterNodes = nodes.stream().filter(n -> after.equals(n.seed().lane())).toList();
        for (Node from : beforeNodes) {
            for (Node to : afterNodes) {
                if (from.index() != to.index()) {
                    edges.get(from.index()).add(to.index());
                }
            }
        }
    }

    private static List<Node> topologicalSort(List<Node> nodes, Map<Integer, Set<Integer>> edges) {
        Map<Integer, Integer> indegree = new LinkedHashMap<>();
        Map<Integer, Node> byIndex = new LinkedHashMap<>();
        for (Node node : nodes) {
            indegree.put(node.index(), 0);
            byIndex.put(node.index(), node);
        }
        for (Set<Integer> targets : edges.values()) {
            for (Integer target : targets) {
                indegree.computeIfPresent(target, (k, v) -> v + 1);
            }
        }
        ArrayDeque<Node> ready = new ArrayDeque<>();
        for (Node node : nodes) {
            if (indegree.getOrDefault(node.index(), 0) == 0) {
                ready.add(node);
            }
        }
        ArrayList<Node> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            Node current = ready.removeFirst();
            ordered.add(current);
            for (Integer target : edges.getOrDefault(current.index(), Set.of())) {
                int next = indegree.computeIfPresent(target, (k, v) -> v - 1);
                if (next == 0) {
                    ready.add(byIndex.get(target));
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            throw new IllegalStateException("cycle_detected");
        }
        return ordered;
    }

    private static Map<String, Object> trace(boolean enabled,
                                             String mode,
                                             List<Seed> seeds,
                                             int edgeCount,
                                             int prunedDuplicateCount,
                                             String failureClass) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("selfask.logicDag.enabled", enabled);
        trace.put("selfask.logicDag.dependencyMode", safeLabel(mode));
        trace.put("selfask.logicDag.nodeCount", seeds == null ? 0 : seeds.size());
        trace.put("selfask.logicDag.edgeCount", Math.max(0, edgeCount));
        trace.put("selfask.logicDag.topologicalOrder", seeds == null
                ? List.of()
                : seeds.stream().map(Seed::lane).toList());
        trace.put("selfask.logicDag.prunedDuplicateCount", Math.max(0, prunedDuplicateCount));
        trace.put("selfask.logicDag.failureClass", safeLabel(failureClass));
        return trace;
    }

    private static DependencyMode inferMode(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (containsAny(q, " vs ", "compare", "comparison", "difference", "different", "contrast",
                "correction", "misunderstanding", "not ", "instead")) {
            return DependencyMode.CONTRAST_REASONING;
        }
        if (containsAny(q, "latest", "recent", "news", "today", "current", "now", "2025", "2026",
                "breaking", "updated")) {
            return DependencyMode.RECENCY_FIRST;
        }
        if (containsAny(q, "who is", "what is", "entity", "alias", "relationship", "related",
                "person", "company", "organization")) {
            return DependencyMode.ENTITY_FIRST;
        }
        return DependencyMode.DEFAULT;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static List<Seed> safeSeeds(List<Seed> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return List.of();
        }
        return seeds.stream()
                .filter(seed -> seed != null && !isBlank(seed.query()))
                .map(seed -> new Seed(seed.lane(), seed.query(), seed.weight()))
                .toList();
    }

    private static String canonical(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", " ")
                .trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String failureClass(Exception e) {
        String text = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("cycle")) {
            return "cycle_detected";
        }
        return e == null ? "other" : safeLabel(e.getClass().getSimpleName());
    }

    private static String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "_")
                .replaceAll("_+", "_");
    }

    record Seed(String lane, String query, double weight) {
        Seed {
            lane = normalizeLane(lane);
            query = query == null ? "" : query.trim();
            weight = Double.isFinite(weight) ? weight : 1.0d;
        }

        private static String normalizeLane(String lane) {
            if (lane == null || lane.isBlank()) {
                return "BFS";
            }
            String normalized = lane.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "BQ", "ER", "RC" -> normalized;
                default -> normalized.length() <= 12 ? normalized : normalized.substring(0, 12);
            };
        }
    }

    record Result(List<Seed> seeds, Map<String, Object> trace) {
        Result {
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
            trace = trace == null ? Map.of() : Map.copyOf(trace);
        }

        String failureClass() {
            Object value = trace.get("selfask.logicDag.failureClass");
            return value == null ? "" : String.valueOf(value);
        }
    }

    private record Node(int index, Seed seed) {
    }

    private record NodeSelection(List<Node> nodes, int prunedDuplicateCount) {
    }

    private record Graph(Map<Integer, Set<Integer>> edges, int edgeCount) {
    }

    private enum DependencyMode {
        ENTITY_FIRST("entity_first"),
        RECENCY_FIRST("recency_first"),
        CONTRAST_REASONING("contrast_reasoning"),
        DEFAULT("default");

        private final String id;

        DependencyMode(String id) {
            this.id = id;
        }
    }
}
