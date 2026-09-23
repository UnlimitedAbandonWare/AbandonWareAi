package com.example.lms.service.rag.langgraph;

import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LangGraphNodeSnapshotRecorder {

    private static final ThreadLocal<CaptureScope> ACTIVE = new ThreadLocal<>();

    private final ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;

    public LangGraphNodeSnapshotRecorder(ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider,
                                         ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this.traceSnapshotStoreProvider = traceSnapshotStoreProvider;
        this.debugEventStoreProvider = debugEventStoreProvider;
    }

    public CaptureScope begin(String runId, String threadIdHash, String queryHash) {
        CaptureScope scope = new CaptureScope(
                runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId,
                threadIdHash == null ? "" : threadIdHash,
                queryHash == null ? "" : queryHash);
        ACTIVE.set(scope);
        return scope;
    }

    public void record(String node, Map<String, Object> inputContext, Map<String, Object> outputContext) {
        CaptureScope scope = ACTIVE.get();
        if (scope == null || scope.closed) {
            return;
        }
        String safeNode = node == null || node.isBlank() ? "unknown" : node;
        String inputId = captureSnapshot(scope, safeNode, "input", inputContext);
        String outputId = captureSnapshot(scope, safeNode, "output", outputContext);
        NodeSnapshot snapshot = new NodeSnapshot(
                safeNode,
                inputId,
                outputId,
                stableMap(inputContext),
                stableMap(outputContext));
        scope.snapshots.add(snapshot);

        Map<String, Object> breadcrumb = new LinkedHashMap<>();
        breadcrumb.put("runId", scope.runId);
        breadcrumb.put("node", safeNode);
        breadcrumb.put("inputSnapshotId", inputId);
        breadcrumb.put("outputSnapshotId", outputId);
        breadcrumb.put("threadIdHash", scope.threadIdHash);
        breadcrumb.put("queryHash", scope.queryHash);
        OrchEventEmitter.breadcrumb("langgraph-contamination", "node", safeNode, breadcrumb);

        DebugEventStore debugStore = debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable();
        if (debugStore != null) {
            debugStore.emit(
                    DebugProbeType.ORCHESTRATION,
                    DebugEventLevel.INFO,
                    "langgraph.contamination.node:" + safeNode,
                    "[AWX2AF2][langgraph][contamination] node snapshot",
                    "RagGraphExecutor." + safeNode,
                    breadcrumb,
                    null);
        }
    }

    private String captureSnapshot(CaptureScope scope,
                                   String node,
                                   String phase,
                                   Map<String, Object> context) {
        String fallbackId = scope.runId + ":" + node + ":" + phase + ":" + (scope.snapshots.size() + 1);
        TraceSnapshotStore store = traceSnapshotStoreProvider == null ? null : traceSnapshotStoreProvider.getIfAvailable();
        if (store == null) {
            return fallbackId;
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("langgraph.contamination.runId", scope.runId);
        trace.put("langgraph.contamination.node", node);
        trace.put("langgraph.contamination.phase", phase);
        trace.put("langgraph.contamination.threadIdHash", scope.threadIdHash);
        trace.put("langgraph.contamination.queryHash", scope.queryHash);
        trace.put("langgraph.contamination.context", stableMap(context));
        String id = store.captureCustom(
                "langgraph_contamination_" + node + "_" + phase,
                "LANGGRAPH",
                node,
                null,
                null,
                trace,
                null);
        return id == null || id.isBlank() ? fallbackId : id;
    }

    private static Map<String, Object> stableMap(Map<String, Object> input) {
        return input == null || input.isEmpty() ? Map.of() : new LinkedHashMap<>(input);
    }

    public static final class CaptureScope implements AutoCloseable {
        private final String runId;
        private final String threadIdHash;
        private final String queryHash;
        private final List<NodeSnapshot> snapshots = new ArrayList<>();
        private boolean closed;

        private CaptureScope(String runId, String threadIdHash, String queryHash) {
            this.runId = runId;
            this.threadIdHash = threadIdHash;
            this.queryHash = queryHash;
        }

        public List<NodeSnapshot> snapshots() {
            return List.copyOf(snapshots);
        }

        @Override
        public void close() {
            closed = true;
            ACTIVE.remove();
        }
    }

    public record NodeSnapshot(
            String node,
            String inputSnapshotId,
            String outputSnapshotId,
            Map<String, Object> inputContext,
            Map<String, Object> outputContext
    ) {
    }
}
