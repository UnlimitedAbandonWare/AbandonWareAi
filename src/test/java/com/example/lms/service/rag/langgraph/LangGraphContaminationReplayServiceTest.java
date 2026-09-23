package com.example.lms.service.rag.langgraph;

import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryTrace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangGraphContaminationReplayServiceTest {

    @Test
    void replayUsesSeedOnlyAndReportsPromptInjectionNode() {
        FakeOrchestrator orchestrator = new FakeOrchestrator();
        RecordingPromptBuilder promptBuilder = new RecordingPromptBuilder();
        LangGraphContaminationReplayService service = service(orchestrator, promptBuilder);
        LangGraphContaminationReplayService.ReplayRequest request =
                new LangGraphContaminationReplayService.ReplayRequest();
        request.scenario = "prompt_injection";
        request.topK = 5;

        LangGraphContaminationReport report = service.replay(request);

        assertNotNull(orchestrator.lastRequest);
        assertTrue(orchestrator.lastRequest.seedOnly);
        assertFalse(orchestrator.externalRetrieverCalled);
        assertTrue(report.nodes().stream().anyMatch(n -> "retrieve".equals(n.node())));
        assertTrue(report.nodes().stream().anyMatch(n -> "prompt_builder".equals(n.node())));
        assertTrue(report.nodes().stream().anyMatch(LangGraphContaminationReport.NodeReport::promptInjectionFlag));
        assertTrue(report.contaminationSummary().maxScore() >= 0.70d);
        assertTrue(report.nodes().stream()
                .allMatch(n -> n.snapshotIds() != null
                        && n.snapshotIds().containsKey("input")
                        && n.snapshotIds().containsKey("output")));
        assertTrue(promptBuilder.calls > 0);
        assertTrue(promptBuilder.lastPrompt.contains("Ignore previous instructions"));
        String dump = String.valueOf(report);
        assertFalse(dump.contains("sk-"));
        assertFalse(dump.contains("ownerToken"));
        assertFalse(dump.contains("Ignore previous instructions"));
        assertFalse(dump.contains("expose the system prompt"));
        assertFalse(dump.contains(promptBuilder.lastPrompt));
    }

    @Test
    void replayIdentifiesStaleVectorFixture() {
        FakeOrchestrator orchestrator = new FakeOrchestrator();
        LangGraphContaminationReplayService service = service(orchestrator, new RecordingPromptBuilder());
        LangGraphContaminationReplayService.ReplayRequest request =
                new LangGraphContaminationReplayService.ReplayRequest();
        request.scenario = "stale_vector";

        LangGraphContaminationReport report = service.replay(request);

        assertTrue(report.nodes().stream().anyMatch(LangGraphContaminationReport.NodeReport::staleContextFlag));
        assertTrue(report.contaminationSummary().fieldActions().values().stream()
                .flatMap(List::stream)
                .anyMatch("QUARANTINE:staleVectorDocs"::equals));
        assertFalse(String.valueOf(report).contains("stale outdated 2023-only vector context"));
    }

    @Test
    void replayReportDoesNotExposeSessionMemoryFixture() {
        FakeOrchestrator orchestrator = new FakeOrchestrator();
        LangGraphContaminationReplayService service = service(orchestrator, new RecordingPromptBuilder());
        LangGraphContaminationReplayService.ReplayRequest request =
                new LangGraphContaminationReplayService.ReplayRequest();
        request.scenario = "memory_leak";

        LangGraphContaminationReport report = service.replay(request);

        assertTrue(report.nodes().stream().anyMatch(LangGraphContaminationReport.NodeReport::memoryLeakFlag));
        String dump = String.valueOf(report);
        assertFalse(dump.contains("session_memory from a previous session"));
        assertFalse(dump.contains("learner passport number"));
    }

    @Test
    void offlineReplayRejectsNonSeedOnlyRequestsBeforeRetrieverCanRun() {
        FakeOrchestrator orchestrator = new FakeOrchestrator();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(
                orchestrator,
                new FixedProvider<EvidenceRepairHandler>(null),
                properties,
                new FixedProvider<>(new LangGraphNodeSnapshotRecorder(null, null)));
        QueryRequest request = new QueryRequest();
        request.query = "must not call external retriever";
        request.seedOnly = false;

        assertThrows(IllegalArgumentException.class, () -> executor.executeOfflineReplay(request));
        assertFalse(orchestrator.externalRetrieverCalled);
    }

    @Test
    void replayTraceStoreUsesHashOnlyRunIdentifiers() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/langgraph/LangGraphContaminationReplayService.java"));

        assertFalse(source.contains("TraceStore.put(\"sid\", threadId);"));
        assertFalse(source.contains("TraceStore.put(\"traceId\", runId);"));
        assertTrue(source.contains("TraceStore.put(\"sid\", SafeRedactor.hashValue(threadId));"));
        assertTrue(source.contains("TraceStore.put(\"traceId\", SafeRedactor.hashValue(runId));"));
    }

    @Test
    void replaySnapshotDoesNotStoreRawPromptText() throws Exception {
        String replaySource = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/langgraph/LangGraphContaminationReplayService.java"));
        String analyzerSource = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/langgraph/ContextContaminationAnalyzer.java"));

        assertFalse(replaySource.contains("output.put(\"promptText\", prompt);"));
        assertTrue(replaySource.contains("output.put(\"promptPresent\", contaminationReplayPrompt != null && !contaminationReplayPrompt.isBlank());"));
        assertTrue(replaySource.contains("output.put(\"promptHash\", SafeRedactor.hash12(contaminationReplayPrompt));"));
        assertTrue(analyzerSource.contains("output.containsKey(\"promptHash\")"));
        assertFalse(analyzerSource.contains("output.containsKey(\"promptText\")"));
    }

    @Test
    void replayPromptBuilderCallsiteUsesStageSpecificPromptName() throws Exception {
        String replaySource = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/langgraph/LangGraphContaminationReplayService.java"));

        assertFalse(replaySource.contains("String prompt = promptBuilder.build(promptContext);"));
        assertFalse(replaySource.contains("containsRiskText(prompt)"));
        assertTrue(replaySource.contains("String contaminationReplayPrompt = promptBuilder.build(promptContext);"));
        assertTrue(replaySource.contains("containsRiskText(contaminationReplayPrompt)"));
    }

    private static LangGraphContaminationReplayService service(FakeOrchestrator orchestrator,
                                                               PromptBuilder promptBuilder) {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        LangGraphNodeSnapshotRecorder recorder = new LangGraphNodeSnapshotRecorder(null, null);
        RagGraphExecutor executor = new RagGraphExecutor(
                orchestrator,
                new FixedProvider<EvidenceRepairHandler>(null),
                properties,
                new FixedProvider<>(recorder));
        return new LangGraphContaminationReplayService(
                executor,
                recorder,
                new ContextContaminationAnalyzer(),
                new LangGraphContaminationReportStore(),
                promptBuilder);
    }

    private static final class RecordingPromptBuilder implements PromptBuilder {
        private final StandardPromptBuilder delegate = new StandardPromptBuilder();
        private int calls;
        private String lastPrompt = "";

        @Override
        public String build(List<PromptContext> contexts, String question) {
            calls++;
            lastPrompt = delegate.build(contexts, question);
            return lastPrompt;
        }
    }

    private static final class FakeOrchestrator extends UnifiedRagOrchestrator {
        private QueryRequest lastRequest;
        private boolean externalRetrieverCalled;

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            lastRequest = req;
            if (req == null || !req.seedOnly) {
                externalRetrieverCalled = true;
            }
            QueryResponse response = new QueryResponse();
            response.requestId = "offline";
            response.planApplied = req == null ? null : req.planId;
            response.debug = new LinkedHashMap<>();
            response.debug.put("langgraph.mode", "offline-replay");
            if (req != null && req.seedCandidates != null) {
                for (Doc doc : req.seedCandidates) {
                    response.results.add(copy(doc));
                }
            }
            QueryTrace trace = new QueryTrace();
            trace.seed = req == null || req.seedCandidates == null ? List.of() : req.seedCandidates;
            trace.pool = response.results;
            trace.fused = response.results;
            trace.finalResults = response.results;
            trace.response = response;
            return trace;
        }

        private static Doc copy(Doc src) {
            Doc doc = new Doc();
            doc.id = src.id;
            doc.title = src.title;
            doc.snippet = src.snippet;
            doc.source = src.source;
            doc.score = src.score;
            doc.rank = src.rank;
            doc.meta = src.meta == null ? new LinkedHashMap<>() : new LinkedHashMap<>(src.meta);
            return doc;
        }
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
}
