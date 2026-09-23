package com.example.lms.service.rag.langgraph;

import com.example.lms.service.rag.SelfAskPlanner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineGraphRagReplayTest {

    @Test
    void offlineProbeRunsGraphExpansionRerankAndLangGraphReplay() throws Exception {
        OfflineGraphRagReplayHarness harness = new OfflineGraphRagReplayHarness();

        OfflineGraphRagReplayHarness.ProbeReport report = harness.run();

        assertEquals(20, report.nodeCount());
        assertEquals(40, report.edgeCount());
        assertEquals(5, report.cases().size());
        assertEquals("unavailable_fallback", report.onnxStatus());
        assertFalse(report.externalRetrieverCalled());
        assertTrue(report.allLanesPresent());
        assertTrue(Files.exists(report.reportPath()));

        for (OfflineGraphRagReplayHarness.ProbeCaseResult result : report.cases()) {
            assertAllLanesPresent(result);
            assertFalse(result.selectedNodes().isEmpty(), result.caseId());
            assertFalse(result.droppedNodes().isEmpty(), result.caseId());
            assertTrue(result.finalResultCount() > 0, result.caseId());
            assertTrue(result.orchestratorDebug().containsKey("stage.fuse"), result.caseId());
            assertEquals(result.finalResultCount(), result.orchestratorDebug().get("stage.dpp"), result.caseId());
            assertEquals(result.finalResultCount(), result.ragEvalStageCounts().get("final"), result.caseId());
            assertFalse(result.langGraphExecutionMode().isBlank(), result.caseId());
            if (result.langGraphExecutionMode().startsWith("fallback:")) {
                assertTrue(result.langGraphDebug().containsKey("langgraph.fallback"), result.caseId());
            }
            assertEquals("ok", result.langGraphDebug().get("langgraph.node.prepare"));
            assertEquals("ok", result.langGraphDebug().get("langgraph.node.retrieve"));
            assertEquals("ok", result.langGraphDebug().get("langgraph.node.finalize"));
            assertTrue(result.langGraphDebug().containsKey("langgraph.node.quality_gate.resultCount"));
            assertEquals(result.finalResultCount(), result.stageCounts().get("finalResults"));
            assertEquals(3, result.stageCounts().get("lanes"));
            assertNoDuplicateDroppedNodes(result);
            assertTrue(result.droppedNodes().stream().allMatch(node -> node.rrfScore() > 0.0d), result.caseId());
            assertEquals("unavailable_fallback", result.onnxStatus());
        }

        String reportJson = Files.readString(report.reportPath());
        assertTrue(reportJson.contains("\"selectedNodes\""));
        assertTrue(reportJson.contains("\"droppedNodes\""));
        assertTrue(reportJson.contains("\"rrfScore\""));
        assertTrue(reportJson.contains("\"dppScore\""));
        assertTrue(reportJson.contains("\"onnxStatus\""));
        assertTrue(reportJson.contains("\"orchestratorDebug\""));
        assertTrue(reportJson.contains("\"ragEvalStageCounts\""));
        assertTrue(reportJson.contains("\"langGraphExecutionMode\""));
        assertReportDoesNotLeakRawInputs(report, reportJson);
    }

    private static void assertAllLanesPresent(OfflineGraphRagReplayHarness.ProbeCaseResult result) {
        Set<SelfAskPlanner.SubQuestionType> lanes = result.lanes().stream()
                .map(OfflineGraphRagReplayHarness.LaneQuery::type)
                .collect(Collectors.toSet());
        assertTrue(lanes.containsAll(List.of(
                SelfAskPlanner.SubQuestionType.BQ,
                SelfAskPlanner.SubQuestionType.ER,
                SelfAskPlanner.SubQuestionType.RC)));
    }

    private static void assertNoDuplicateDroppedNodes(OfflineGraphRagReplayHarness.ProbeCaseResult result) {
        long distinct = result.droppedNodes().stream()
                .map(OfflineGraphRagReplayHarness.DroppedNode::nodeId)
                .distinct()
                .count();
        assertEquals(result.droppedNodes().size(), distinct, result.caseId());
    }

    private static void assertReportDoesNotLeakRawInputs(OfflineGraphRagReplayHarness.ProbeReport report,
                                                         String reportJson) {
        for (String forbidden : List.of("sk-", "ownerToken", "api_key", "offline-graphrag-q")) {
            assertFalse(reportJson.contains(forbidden), forbidden);
        }
        for (String query : OfflineGraphRagReplayHarness.rawProbeQueriesForRedactionTest()) {
            assertFalse(reportJson.contains(query), query);
        }
        for (String threadId : OfflineGraphRagReplayHarness.rawThreadIdsForRedactionTest()) {
            assertFalse(reportJson.contains(threadId), threadId);
        }
        for (OfflineGraphRagReplayHarness.ProbeCaseResult result : report.cases()) {
            for (OfflineGraphRagReplayHarness.LaneQuery lane : result.lanes()) {
                assertFalse(reportJson.contains(lane.text()), lane.text());
            }
        }
    }
}
