package com.example.lms.replay;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import java.util.ArrayList;
import java.util.List;

public final class OfflineReplayRunner {

    private OfflineReplayRunner() {
    }

    public static List<String> parseJsonlLines(List<String> lines) {
        List<String> accepted = new ArrayList<>();
        if (lines == null) {
            return accepted;
        }
        for (String line : lines) {
            try {
                if (line == null || line.isBlank()) {
                    continue;
                }
                if (!line.trim().startsWith("{")) {
                    throw new IllegalArgumentException("invalid_jsonl_line");
                }
                accepted.add(SafeRedactor.hash12(line));
            } catch (RuntimeException ex) {
                TraceStore.inc("offlineReplay.parse.failSoft.count");
                TraceStore.put("offlineReplay.parse.failSoft.failureClass", "offline_replay_parse_failed");
                TraceStore.put("offlineReplay.parse.failSoft.errorType", replayErrorType(ex));
                TraceStore.put("offlineReplay.parse.failSoft.errorLabel",
                        SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            }
        }
        return accepted;
    }

    private static String replayErrorType(Exception ex) {
        return SafeRedactor.traceLabelOrFallback(ex == null ? null : ex.getClass().getSimpleName(), "unknown");
    }
}
