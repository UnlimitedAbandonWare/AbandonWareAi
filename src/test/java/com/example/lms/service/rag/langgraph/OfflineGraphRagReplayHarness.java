package com.example.lms.service.rag.langgraph;

import com.example.lms.service.rag.SelfAskPlanner;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class OfflineGraphRagReplayHarness {

    static final Path REPORT_PATH = Path.of(
            "build", "reports", "offline-graphrag", "offline-graph-rag-report.json");

    private static final int MAX_DEPTH = 2;
    private static final int SELECTED_TOP_K = 8;
    private static final String ONNX_STATUS = "unavailable_fallback";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    ProbeReport run() throws IOException {
        MockGraph graph = MockGraph.create();
        UnifiedRagOrchestrator orchestrator = realOrchestrator();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(
                orchestrator,
                new FixedProvider<EvidenceRepairHandler>(null),
                properties);

        List<ProbeCaseResult> cases = new ArrayList<>();
        for (ProbeCase probeCase : probeCases()) {
            cases.add(runCase(graph, executor, probeCase));
        }

        ProbeReport report = new ProbeReport(
                graph.nodes().size(),
                graph.edges().size(),
                MAX_DEPTH,
                cases,
                false,
                ONNX_STATUS,
                REPORT_PATH);
        Files.createDirectories(REPORT_PATH.getParent());
        objectMapper.writeValue(REPORT_PATH.toFile(), report.toReportMap());
        return report;
    }

    private static UnifiedRagOrchestrator realOrchestrator() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "embeddingModel", new DeterministicEmbeddingModel());
        ReflectionTestUtils.setField(orchestrator, "dppDiversityReranker",
                new DppDiversityReranker(new DppDiversityReranker.Config(0.45d, SELECTED_TOP_K)));
        return orchestrator;
    }

    private ProbeCaseResult runCase(MockGraph graph, RagGraphExecutor executor, ProbeCase probeCase) {
        List<LaneQuery> lanes = deterministicLanes(probeCase);
        List<GraphCandidate> expanded = expand(graph, lanes);
        List<GraphCandidate> fused = applyRrf(expanded);
        List<GraphCandidate> prepared = applyDpp(probeCase, fused);
        List<GraphCandidate> seeded = withPreparationDppScores(fused, prepared);

        QueryRequest request = new QueryRequest();
        request.query = probeCase.query();
        request.threadId = "offline-graphrag-" + probeCase.id();
        request.planId = "offline.graph-rag.probe.v1";
        request.topK = SELECTED_TOP_K;
        request.seedOnly = true;
        request.seedMode = "candidates";
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableSelfAsk = true;
        request.enableDiversity = true;
        request.enableBiEncoder = false;
        request.enableOnnx = false;
        request.seedCandidates = toDocs(seeded);

        QueryResponse response = executor.executeOfflineReplay(request);
        List<SelectedNode> selectedNodes = selectedNodes(response);
        List<DroppedNode> dropped = droppedNodes(seeded, selectedNodes);
        Map<String, Integer> stageCounts = stageCounts(lanes, expanded, fused, prepared, seeded, dropped, response);
        return new ProbeCaseResult(
                probeCase.id(),
                SafeRedactor.hash12(probeCase.query()),
                RagGraphExecutor.hashThreadId(request.threadId),
                lanes,
                selectedNodes,
                dropped,
                stageCounts,
                safeLangGraphDebug(response),
                safeOrchestratorDebug(response),
                ragEvalStageCounts(response),
                response.results == null ? 0 : response.results.size(),
                langGraphExecutionMode(response),
                ONNX_STATUS);
    }

    private static List<ProbeCase> probeCases() {
        return List.of(
                new ProbeCase("q01", "GraphRAG offline trace validation"),
                new ProbeCase("q02", "Self Ask relation expansion evidence flow"),
                new ProbeCase("q03", "RRF ONNX DPP reranking quality check"),
                new ProbeCase("q04", "Plan DSL breadcrumb observability probe"),
                new ProbeCase("q05", "Knowledge graph future tree virtual matrix"));
    }

    static List<String> rawProbeQueriesForRedactionTest() {
        return probeCases().stream().map(ProbeCase::query).toList();
    }

    static List<String> rawThreadIdsForRedactionTest() {
        return probeCases().stream()
                .map(probeCase -> "offline-graphrag-" + probeCase.id())
                .toList();
    }

    private static List<LaneQuery> deterministicLanes(ProbeCase probeCase) {
        String query = probeCase.query();
        return List.of(
                new LaneQuery(SelfAskPlanner.SubQuestionType.BQ,
                        query + " factual context terminology"),
                new LaneQuery(SelfAskPlanner.SubQuestionType.ER,
                        query + " entity relation alias edge"),
                new LaneQuery(SelfAskPlanner.SubQuestionType.RC,
                        query + " correction counterexample missing context"));
    }

    private static List<GraphCandidate> expand(MockGraph graph, List<LaneQuery> lanes) {
        Map<String, GraphCandidate> candidates = new LinkedHashMap<>();
        for (LaneQuery lane : lanes) {
            List<GraphNode> seeds = graph.nodes().stream()
                    .map(node -> Map.entry(node, overlap(tokens(lane.text()), node.tags())))
                    .filter(entry -> entry.getValue() > 0)
                    .sorted(Comparator.<Map.Entry<GraphNode, Integer>>comparingInt(Map.Entry::getValue)
                            .reversed()
                            .thenComparing(entry -> entry.getKey().id()))
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .toList();
            if (seeds.isEmpty()) {
                seeds = List.of(graph.nodes().get(0));
            }
            for (GraphNode seed : seeds) {
                traverse(graph, candidates, lane, seed);
            }
        }
        return candidates.values().stream()
                .sorted(Comparator.comparingDouble(GraphCandidate::rawScore).reversed()
                        .thenComparing(candidate -> candidate.node().id()))
                .toList();
    }

    private static void traverse(MockGraph graph,
                                 Map<String, GraphCandidate> candidates,
                                 LaneQuery lane,
                                 GraphNode seed) {
        ArrayDeque<NodeVisit> queue = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        queue.add(new NodeVisit(seed, 0, "seed", 1.0d));
        while (!queue.isEmpty()) {
            NodeVisit visit = queue.removeFirst();
            if (!seen.add(visit.node().id() + ":" + visit.depth())) {
                continue;
            }
            int tokenOverlap = overlap(tokens(lane.text()), visit.node().tags());
            double score = tokenOverlap + visit.confidence() - (visit.depth() * 0.35d);
            String key = lane.type().name() + ":" + visit.node().id();
            candidates.merge(
                    key,
                    new GraphCandidate(lane.type(), visit.node(), visit.depth(), visit.edgeType(), score, 0.0d, 0.0d),
                    (left, right) -> left.rawScore() >= right.rawScore() ? left : right);
            if (visit.depth() >= MAX_DEPTH) {
                continue;
            }
            for (GraphEdge edge : graph.outgoing(visit.node().id())) {
                GraphNode next = graph.byId(edge.to());
                if (next != null) {
                    queue.addLast(new NodeVisit(next, visit.depth() + 1, edge.type(), edge.confidence()));
                }
            }
        }
    }

    private static List<GraphCandidate> applyRrf(List<GraphCandidate> expanded) {
        Map<String, List<GraphCandidate>> byLane = expanded.stream()
                .collect(Collectors.groupingBy(
                        candidate -> candidate.lane().name(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, Double> rrfByNode = new LinkedHashMap<>();
        for (Map.Entry<String, List<GraphCandidate>> entry : byLane.entrySet()) {
            List<GraphCandidate> laneRank = entry.getValue().stream()
                    .sorted(Comparator.comparingDouble(GraphCandidate::rawScore).reversed())
                    .toList();
            double laneWeight = laneWeight(entry.getKey());
            for (int i = 0; i < laneRank.size(); i++) {
                GraphCandidate candidate = laneRank.get(i);
                rrfByNode.merge(candidate.node().id(), laneWeight / (60.0d + i + 1), Double::sum);
            }
        }
        Map<String, GraphCandidate> bestByNode = new LinkedHashMap<>();
        for (GraphCandidate candidate : expanded) {
            bestByNode.merge(candidate.node().id(), candidate,
                    (left, right) -> left.rawScore() >= right.rawScore() ? left : right);
        }
        return bestByNode.values().stream()
                .map(candidate -> candidate.withRrf(round4(rrfByNode.getOrDefault(candidate.node().id(), 0.0d))))
                .sorted(Comparator.comparingDouble(GraphCandidate::rrfScore).reversed()
                        .thenComparing(candidate -> candidate.node().id()))
                .toList();
    }

    private static List<GraphCandidate> applyDpp(ProbeCase probeCase, List<GraphCandidate> fused) {
        if (fused.isEmpty()) {
            return List.of();
        }
        DppDiversityReranker reranker = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.45d, SELECTED_TOP_K));
        List<GraphCandidate> selected = reranker.rerank(
                fused,
                probeCase.query(),
                Math.min(SELECTED_TOP_K, fused.size()),
                candidate -> candidate.node().title() + " " + candidate.node().summary(),
                candidate -> Math.max(0.0d, candidate.rrfScore()));
        List<GraphCandidate> scored = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            scored.add(selected.get(i).withDpp(round4(1.0d / (i + 1))));
        }
        return scored;
    }

    private static List<GraphCandidate> withPreparationDppScores(List<GraphCandidate> fused,
                                                                 List<GraphCandidate> prepared) {
        Map<String, Double> dppByNode = prepared.stream()
                .collect(Collectors.toMap(
                        candidate -> candidate.node().id(),
                        GraphCandidate::dppScore,
                        Math::max,
                        LinkedHashMap::new));
        return fused.stream()
                .map(candidate -> candidate.withDpp(round4(dppByNode.getOrDefault(candidate.node().id(), 0.0d))))
                .toList();
    }

    private static List<DroppedNode> droppedNodes(List<GraphCandidate> seeded,
                                                  List<SelectedNode> selected) {
        Set<String> selectedIds = selected.stream()
                .map(SelectedNode::nodeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, DroppedNode> droppedByNode = new LinkedHashMap<>();
        for (GraphCandidate candidate : seeded) {
            if (!selectedIds.contains(candidate.node().id())) {
                droppedByNode.putIfAbsent(candidate.node().id(), new DroppedNode(
                        candidate.node().id(),
                        candidate.node().title(),
                        "below_real_orchestrator_cutoff",
                        candidate.depth(),
                        round4(candidate.rrfScore())));
            }
        }
        return droppedByNode.values().stream()
                .filter(node -> node.rrfScore() > 0.0d)
                .limit(12)
                .toList();
    }

    private static List<SelectedNode> selectedNodes(QueryResponse response) {
        if (response == null || response.results == null || response.results.isEmpty()) {
            return List.of();
        }
        List<SelectedNode> selected = new ArrayList<>();
        for (Doc doc : response.results) {
            Map<String, Object> meta = doc.meta == null ? Map.of() : doc.meta;
            String nodeId = stringMeta(meta, "graphNodeId", doc.id);
            String lane = stringMeta(meta, "lane", "UNKNOWN");
            int depth = intMeta(meta, "depth", -1);
            double rrfScore = doubleMeta(meta, "rrfScore", doc.score);
            double dppScore = doc.rank > 0 ? round4(1.0d / doc.rank) : doubleMeta(meta, "dppScore", 0.0d);
            selected.add(new SelectedNode(
                    nodeId,
                    doc.title,
                    lane,
                    depth,
                    stringMeta(meta, "edgeType", "unknown"),
                    round4(rrfScore),
                    round4(dppScore),
                    SafeRedactor.hash12(nodeId + ":" + lane)));
        }
        return selected;
    }

    private static List<Doc> toDocs(List<GraphCandidate> selected) {
        List<Doc> docs = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            GraphCandidate candidate = selected.get(i);
            Doc doc = new Doc();
            doc.id = "graph:" + candidate.node().id();
            doc.title = candidate.node().title();
            doc.snippet = candidate.node().summary();
            doc.source = "KG";
            doc.score = candidate.dppScore() > 0.0d ? candidate.dppScore() : candidate.rrfScore();
            doc.rank = i + 1;
            doc.meta = new LinkedHashMap<>();
            doc.meta.put("graphNodeId", candidate.node().id());
            doc.meta.put("lane", candidate.lane().name());
            doc.meta.put("edgeType", candidate.edgeType());
            doc.meta.put("depth", candidate.depth());
            doc.meta.put("rrfScore", candidate.rrfScore());
            doc.meta.put("dppScore", candidate.dppScore());
            doc.meta.put("breadcrumbHash", SafeRedactor.hash12(candidate.node().id() + ":" + candidate.lane()));
            docs.add(doc);
        }
        return docs;
    }

    private static Map<String, Integer> stageCounts(List<LaneQuery> lanes,
                                                    List<GraphCandidate> expanded,
                                                    List<GraphCandidate> fused,
                                                    List<GraphCandidate> prepared,
                                                    List<GraphCandidate> seeded,
                                                    List<DroppedNode> dropped,
                                                    QueryResponse response) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("lanes", lanes.size());
        out.put("expanded", expanded.size());
        out.put("graphFixtureFused", fused.size());
        out.put("graphFixtureDpp", prepared.size());
        out.put("seedCandidates", seeded.size());
        out.put("dropped", dropped.size());
        out.put("finalResults", response == null || response.results == null ? 0 : response.results.size());
        return out;
    }

    private static Map<String, Object> safeLangGraphDebug(QueryResponse response) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (response == null || response.debug == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : response.debug.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith("langgraph.")) {
                out.put(key, safeDebugValue(key, entry.getValue()));
            }
        }
        return out;
    }

    private static Map<String, Object> safeOrchestratorDebug(QueryResponse response) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (response == null || response.debug == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : response.debug.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (key.startsWith("stage.")
                    || key.startsWith("seed.")
                    || key.startsWith("rag.eval.")
                    || key.equals("selfAsk")
                    || key.equals("planDsl")
                    || key.equals("retrieval")
                    || key.equals("fallback")) {
                out.put(key, safeDebugValue(key, entry.getValue()));
            }
        }
        return out;
    }

    private static Map<String, Integer> ragEvalStageCounts(QueryResponse response) {
        if (response == null || response.debug == null) {
            return Map.of();
        }
        Object value = response.debug.get("rag.eval.stageCounts");
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            int count = 0;
            if (entry.getValue() instanceof Number number) {
                count = number.intValue();
            }
            out.put(String.valueOf(entry.getKey()), count);
        }
        return out;
    }

    private static String langGraphExecutionMode(QueryResponse response) {
        if (response == null || response.debug == null) {
            return "unknown";
        }
        Object fallback = response.debug.get("langgraph.fallback");
        if (fallback != null) {
            return "fallback:" + SafeRedactor.safeMessage(String.valueOf(fallback), 80);
        }
        Object enabled = response.debug.get("langgraph.enabled");
        return Boolean.TRUE.equals(enabled) ? "graph" : "unknown";
    }

    private static Object safeDebugValue(String key, Object value) {
        if (value instanceof String text && Set.of(
                "langgraph.mode",
                "langgraph.fallback",
                "langgraph.invokeFailureReason",
                "langgraph.node.prepare",
                "langgraph.node.retrieve",
                "langgraph.node.finalize",
                "langgraph.emptyReason",
                "langgraph.failureReason",
                "langgraph.checkpoint.backend",
                "langgraph.checkpoint.disabledReason"
        ).contains(key)) {
            return SafeRedactor.safeMessage(text, 120);
        }
        return SafeRedactor.diagnosticValue(key, value, 200);
    }

    private static int overlap(Set<String> tokens, Set<String> tags) {
        int hits = 0;
        for (String token : tokens) {
            if (tags.contains(token)) {
                hits++;
            }
        }
        return hits;
    }

    private static Set<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private static double laneWeight(String lane) {
        return switch (lane) {
            case "BQ" -> 1.0d;
            case "ER" -> 0.95d;
            case "RC" -> 0.90d;
            default -> 0.80d;
        };
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static String stringMeta(Map<String, Object> meta, String key, String fallback) {
        Object value = meta.get(key);
        String text = value == null ? fallback : String.valueOf(value);
        return text == null ? "" : text;
    }

    private static int intMeta(Map<String, Object> meta, String key, int fallback) {
        Object value = meta.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double doubleMeta(Map<String, Object> meta, String key, double fallback) {
        Object value = meta.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static final class FixedProvider<T> implements ObjectProvider<T> {
        private final T value;

        private FixedProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }
    }

    private static final class DeterministicEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(Embedding.from(vector(text)));
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return embed(textSegment == null ? "" : textSegment.text());
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            List<Embedding> embeddings = new ArrayList<>();
            if (textSegments != null) {
                for (TextSegment segment : textSegments) {
                    embeddings.add(Embedding.from(vector(segment == null ? "" : segment.text())));
                }
            }
            return Response.from(embeddings);
        }

        private static float[] vector(String text) {
            float[] values = new float[8];
            String safe = text == null ? "" : text;
            for (int i = 0; i < safe.length(); i++) {
                values[i % values.length] += (safe.charAt(i) % 31) / 31.0f;
            }
            return values;
        }
    }

    record ProbeReport(
            int nodeCount,
            int edgeCount,
            int maxDepth,
            List<ProbeCaseResult> cases,
            boolean externalRetrieverCalled,
            String onnxStatus,
            Path reportPath
    ) {
        boolean allLanesPresent() {
            return cases.stream().allMatch(ProbeCaseResult::hasAllLanes);
        }

        Map<String, Object> toReportMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reportType", "offline-graphrag-probe");
            out.put("graph", Map.of("nodeCount", nodeCount, "edgeCount", edgeCount, "maxDepth", maxDepth));
            out.put("sampleCount", cases.size());
            out.put("onnxStatus", onnxStatus);
            out.put("externalRetrieverCalled", externalRetrieverCalled);
            out.put("allLanesPresent", allLanesPresent());
            out.put("cases", cases.stream().map(ProbeCaseResult::toReportMap).toList());
            return out;
        }
    }

    record ProbeCaseResult(
            String caseId,
            String queryHash,
            String threadIdHash,
            List<LaneQuery> lanes,
            List<SelectedNode> selectedNodes,
            List<DroppedNode> droppedNodes,
            Map<String, Integer> stageCounts,
            Map<String, Object> langGraphDebug,
            Map<String, Object> orchestratorDebug,
            Map<String, Integer> ragEvalStageCounts,
            int finalResultCount,
            String langGraphExecutionMode,
            String onnxStatus
    ) {
        boolean hasAllLanes() {
            Set<SelfAskPlanner.SubQuestionType> laneSet = lanes.stream()
                    .map(LaneQuery::type)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return laneSet.containsAll(List.of(
                    SelfAskPlanner.SubQuestionType.BQ,
                    SelfAskPlanner.SubQuestionType.ER,
                    SelfAskPlanner.SubQuestionType.RC));
        }

        Map<String, Object> toReportMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("caseId", caseId);
            out.put("queryHash", queryHash);
            out.put("threadIdHash", threadIdHash);
            out.put("lanes", lanes.stream().map(LaneQuery::toReportMap).toList());
            out.put("selectedNodes", selectedNodes);
            out.put("droppedNodes", droppedNodes);
            out.put("stageCounts", stageCounts);
            out.put("langGraphDebug", langGraphDebug);
            out.put("orchestratorDebug", orchestratorDebug);
            out.put("ragEvalStageCounts", ragEvalStageCounts);
            out.put("finalResultCount", finalResultCount);
            out.put("langGraphExecutionMode", langGraphExecutionMode);
            out.put("onnxStatus", onnxStatus);
            return out;
        }
    }

    record ProbeCase(String id, String query) {
    }

    record LaneQuery(SelfAskPlanner.SubQuestionType type, String text) {
        Map<String, Object> toReportMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lane", type.name());
            out.put("queryHash", SafeRedactor.hash12(text));
            out.put("queryLength", text == null ? 0 : text.length());
            return out;
        }
    }

    record GraphNode(String id, String title, String summary, Set<String> tags) {
    }

    record GraphEdge(String from, String to, String type, double confidence) {
    }

    record NodeVisit(GraphNode node, int depth, String edgeType, double confidence) {
    }

    record GraphCandidate(
            SelfAskPlanner.SubQuestionType lane,
            GraphNode node,
            int depth,
            String edgeType,
            double rawScore,
            double rrfScore,
            double dppScore
    ) {
        GraphCandidate withRrf(double score) {
            return new GraphCandidate(lane, node, depth, edgeType, rawScore, score, dppScore);
        }

        GraphCandidate withDpp(double score) {
            return new GraphCandidate(lane, node, depth, edgeType, rawScore, rrfScore, score);
        }

        SelectedNode toSelectedNode() {
            return new SelectedNode(
                    node.id(),
                    node.title(),
                    lane.name(),
                    depth,
                    edgeType,
                    round4(rrfScore),
                    round4(dppScore),
                    SafeRedactor.hash12(node.id() + ":" + lane.name()));
        }
    }

    record SelectedNode(
            String nodeId,
            String title,
            String lane,
            int depth,
            String edgeType,
            double rrfScore,
            double dppScore,
            String breadcrumbHash
    ) {
    }

    record DroppedNode(String nodeId, String title, String reason, int depth, double rrfScore) {
    }

    record MockGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
        static MockGraph create() {
            List<GraphNode> nodes = List.of(
                    node("n01", "GraphRAG Root", "Node and edge based retrieval anchor.", "graphrag", "graph", "rag", "node", "edge"),
                    node("n02", "Offline Replay", "Seed-only replay fixture for local verification.", "offline", "replay", "test", "fixture"),
                    node("n03", "Self Ask Lanes", "BQ ER RC subquestion split for long-tail queries.", "self", "ask", "selfask", "bq", "er", "rc", "subquestion"),
                    node("n04", "Knowledge Expansion", "Knowledge graph entity expansion across relationships.", "knowledge", "kg", "entity", "relation", "expansion"),
                    node("n05", "Evidence Collector", "Evidence collection with source and trace boundaries.", "evidence", "collector", "source", "trace"),
                    node("n06", "RRF Fusion", "Reciprocal-rank fusion across graph evidence branches.", "rrf", "fusion", "ranking"),
                    node("n07", "ONNX Rerank Gate", "Cross-encoder rerank gate with offline fallback status.", "onnx", "crossencoder", "rerank", "fallback"),
                    node("n08", "DPP Diversity", "Diversity reranking for duplicate suppression.", "dpp", "diversity", "reranking"),
                    node("n09", "MLA Breadcrumbs", "Breadcrumb trace fields for offline observability.", "mla", "breadcrumb", "trace", "observability"),
                    node("n10", "Plan DSL Policy", "Plan DSL metadata for controlled orchestration.", "plan", "dsl", "policy"),
                    node("n11", "Vector Evidence", "Vector evidence lane for embedding-backed retrieval.", "vector", "embedding", "evidence"),
                    node("n12", "Web Evidence", "Authority and freshness web evidence lane.", "web", "authority", "freshness", "evidence"),
                    node("n13", "Memory Boundary", "Session memory boundary and cross-session guard.", "memory", "session", "boundary"),
                    node("n14", "Quality Gate", "Quality validation before answer candidate acceptance.", "quality", "validation", "gate"),
                    node("n15", "Stale Context", "Stale context candidate for rejection tests.", "stale", "context", "reject"),
                    node("n16", "Noise Candidate", "Low-signal unrelated candidate for drop reporting.", "noise", "unrelated", "drop"),
                    node("n17", "Soak Probe Metrics", "Soak probe metrics for retrieval health.", "soak", "probe", "metrics"),
                    node("n18", "Future Tree Matrix", "Virtual matrix and future tree planning evidence.", "future", "tree", "virtual", "matrix"),
                    node("n19", "Edge Confidence", "Confidence scoring for graph edges.", "confidence", "edge", "score"),
                    node("n20", "Answer Candidate", "Answer candidate verification and final evidence.", "answer", "candidate", "verify"));

            List<GraphEdge> edges = List.of(
                    edge("n01", "n02", "verifies_with", 0.94), edge("n01", "n03", "branches_to", 0.93),
                    edge("n01", "n04", "expands_to", 0.92), edge("n01", "n05", "collects", 0.91),
                    edge("n02", "n06", "feeds", 0.89), edge("n02", "n17", "measures", 0.88),
                    edge("n03", "n04", "asks_for", 0.90), edge("n03", "n10", "uses_plan", 0.84),
                    edge("n04", "n05", "produces", 0.91), edge("n04", "n19", "scores_edge", 0.86),
                    edge("n05", "n06", "fuses", 0.88), edge("n05", "n11", "includes", 0.78),
                    edge("n05", "n12", "includes", 0.79), edge("n06", "n08", "diversifies", 0.86),
                    edge("n06", "n07", "reranks", 0.84), edge("n07", "n14", "gates", 0.82),
                    edge("n08", "n14", "gates", 0.83), edge("n09", "n17", "observes", 0.81),
                    edge("n10", "n09", "emits", 0.80), edge("n11", "n15", "may_contain", 0.60),
                    edge("n12", "n14", "supports", 0.82), edge("n13", "n09", "breadcrumbs", 0.74),
                    edge("n14", "n20", "accepts", 0.89), edge("n15", "n16", "drops_with", 0.50),
                    edge("n17", "n20", "reports", 0.77), edge("n18", "n01", "relates", 0.72),
                    edge("n18", "n10", "planned_by", 0.73), edge("n19", "n06", "weights", 0.76),
                    edge("n20", "n09", "summarizes", 0.75), edge("n02", "n05", "captures", 0.80),
                    edge("n03", "n06", "weights", 0.78), edge("n04", "n18", "projects", 0.69),
                    edge("n08", "n16", "suppresses", 0.61), edge("n10", "n14", "guards", 0.79),
                    edge("n12", "n06", "feeds", 0.77), edge("n11", "n06", "feeds", 0.76),
                    edge("n19", "n14", "normalizes", 0.74), edge("n17", "n09", "logs", 0.78),
                    edge("n13", "n15", "guards_stale", 0.66), edge("n07", "n20", "finalizes", 0.81));
            return new MockGraph(nodes, edges);
        }

        GraphNode byId(String id) {
            for (GraphNode node : nodes) {
                if (node.id().equals(id)) {
                    return node;
                }
            }
            return null;
        }

        List<GraphEdge> outgoing(String id) {
            return edges.stream()
                    .filter(edge -> edge.from().equals(id))
                    .toList();
        }
    }

    private static GraphNode node(String id, String title, String summary, String... tags) {
        return new GraphNode(id, title, summary, new LinkedHashSet<>(List.of(tags)));
    }

    private static GraphEdge edge(String from, String to, String type, double confidence) {
        return new GraphEdge(from, to, type, confidence);
    }
}
