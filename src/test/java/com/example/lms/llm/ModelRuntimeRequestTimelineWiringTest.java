package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRuntimeRequestTimelineWiringTest {

    @Test
    void controllerDispatchAndWorkflowPendingShareTheInternalTimelinePointer() throws Exception {
        String controller = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"), StandardCharsets.UTF_8);
        String workflow = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"), StandardCharsets.UTF_8);

        assertTrue(controller.contains("modelRuntimeHealthTracker.beginRequestTimeline(requestId, sessionId)"));
        assertTrue(controller.contains(
                "TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId)"));
        assertTrue(controller.contains("timelineId, \"dispatch\", modelId, null, \"none\""));
        assertTrue(workflow.contains(
                "TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY)"));
        int captureBeforeClear = workflow.indexOf("Object requestTimelineIdBeforeTraceClear");
        int workflowTraceClear = workflow.indexOf("TraceStore.clear()", captureBeforeClear);
        int restoreAfterClear = workflow.indexOf("TraceStore.putInternal(", workflowTraceClear);
        int pendingAfterRestore = workflow.indexOf(
                "recordModelRequestTimelinePhase(\"pending\", req == null ? null : req.getModel(), \"none\")",
                restoreAfterClear);
        assertTrue(captureBeforeClear >= 0 && workflowTraceClear > captureBeforeClear
                && restoreAfterClear > workflowTraceClear && pendingAfterRestore > restoreAfterClear,
                "the request timeline pointer must survive ChatWorkflow's initial TraceStore.clear()");
        int factoryBuild = workflow.indexOf("dynamicChatModelFactory.lcWithTimeout(");
        int endpointCaptureStart = workflow.lastIndexOf(
                "TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true)",
                factoryBuild);
        int endpointCaptureClear = workflow.indexOf(
                "TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, null)",
                factoryBuild);
        int endpointEnrichment = workflow.indexOf(
                "recordModelRequestTimelinePhase(\"pending\", resolved, \"none\")", factoryBuild);
        assertTrue(endpointCaptureStart >= 0 && factoryBuild > endpointCaptureStart
                        && endpointCaptureClear > factoryBuild && endpointEnrichment > endpointCaptureClear,
                "only the application-owned primary factory call may enrich the selected endpoint");
        assertFalse(workflow.contains("recordModelRequestTimelinePhase(\"terminal\""),
                "ChatWorkflow must not preempt the controller's final request outcome");
        int firstStreamCancelCheck = controller.indexOf(
                "streamCancelledFinal || isStreamRunCancelled(runContextRef.get(), finalSessionId)");
        int doneEmit = controller.indexOf("Sinks.EmitResult finalEmitResult", firstStreamCancelCheck);
        int finalStreamCancelCheck = controller.lastIndexOf(
                "isStreamRunCancelled(runContextRef.get(), finalSessionId)", doneEmit);
        int postEmitCancelCheck = controller.indexOf(
                "isStreamRunCancelled(runContextRef.get(), finalSessionId)", doneEmit);
        int streamTerminal = controller.indexOf("recordModelRequestTerminal(", postEmitCancelCheck);
        assertTrue(firstStreamCancelCheck >= 0 && finalStreamCancelCheck > firstStreamCancelCheck
                        && doneEmit > finalStreamCancelCheck && postEmitCancelCheck > doneEmit
                        && streamTerminal > postEmitCancelCheck,
                "stream success may terminalize only after the final cancellation check");
        assertTrue(controller.contains("recordModelRequestTerminal(\"cancelled\""));
        assertTrue(controller.contains("recordModelRequestTerminal(\"error\""));
        int terminalHelper = controller.indexOf("private void recordModelRequestTerminal(");
        int nextHelper = controller.indexOf("private ", terminalHelper + 8);
        String terminalHelperSource = controller.substring(terminalHelper, nextHelper);
        assertFalse(terminalHelperSource.contains("TraceStore.get("),
                "controller terminal ownership must use its local timeline id after trace cleanup");
        assertFalse(controller.contains("new ModelRuntimeHealthTracker()"));
        assertFalse(workflow.contains("new ModelRuntimeHealthTracker()"));
    }

    @Test
    void routerUsesTheSharedTrackerAndWorkflowCannotBackfillAStaleFactoryEndpoint() throws Exception {
        String autoConfiguration = Files.readString(
                Path.of("main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java"),
                StandardCharsets.UTF_8);
        String workflow = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"), StandardCharsets.UTF_8);

        assertTrue(autoConfiguration.contains("ObjectProvider<ModelRuntimeHealthTracker>"),
                "router auto-configuration must receive the application singleton tracker");
        assertTrue(autoConfiguration.contains("modelRuntimeHealthTrackerProvider.getIfAvailable()"),
                "the shared tracker must be passed into LlmRouterAspect");
        int helper = workflow.indexOf("private void recordModelRequestTimelinePhase(");
        int nextHelper = workflow.indexOf("private ", helper + 8);
        String helperSource = workflow.substring(helper, nextHelper);
        assertFalse(helperSource.contains("llm.factory.baseUrlHost"),
                "workflow-level pending must not claim a stale or auxiliary factory endpoint");
    }

    @Test
    void fallbackAttemptsUseTheCapturedPointerWithoutClaimingControllerTerminalOrDiagnosticProbe() throws Exception {
        String aspect = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);
        String fallback = Files.readString(
                Path.of("main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java"),
                StandardCharsets.UTF_8);

        assertTrue(aspect.contains("String requestTimelineId = capturedRequestTimelineId()"));
        assertTrue(aspect.contains("primaryRoute.get()"));
        assertTrue(aspect.contains("fallbackRoute.get()") || aspect.contains("fallbackRoute::get"));
        assertTrue(fallback.contains("hasMatchingTimelineContext()"));
        assertTrue(fallback.contains("healthTracker.recordRequestAttempt("));
        assertFalse(fallback.contains("recordEndpointProbe("),
                "live inference attempts are not post-terminal repair probes");
        assertFalse(fallback.contains("recordRequestPhase("),
                "FallbackAwareChatModel must not preempt the controller's terminal latch");
    }
}
