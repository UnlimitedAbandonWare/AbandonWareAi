package com.example.lms.replay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OfflineReplayRunnerTraceContractTest {

    @Test
    void parseFailureCatchLeavesRedactedTraceStoreBreadcrumbWithoutRawJsonl() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/replay/OfflineReplayRunner.java"));

        assertTrue(source.contains("TraceStore.inc(\"offlineReplay.parse.failSoft.count\")"));
        assertTrue(source.contains(
                "TraceStore.put(\"offlineReplay.parse.failSoft.failureClass\", \"offline_replay_parse_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"offlineReplay.parse.failSoft.errorType\", replayErrorType(ex))"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback"));

        assertFalse(source.contains("TraceStore.put(\"offlineReplay.parse.failSoft.line\", line)"));
        assertFalse(source.contains("TraceStore.put(\"offlineReplay.parse.failSoft.rawLine\""));
        assertFalse(source.contains("System.err.println(\"Failed to parse replay line: \" + line"));
        assertFalse(source.contains("ex.printStackTrace(System.err)"));
    }
}
