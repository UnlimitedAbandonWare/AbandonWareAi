package com.example.lms.conversation.archive;

import java.util.List;
import java.util.Map;

public record ConversationArchiveIngestReport(
        boolean ok,
        String sessionIdHash,
        int zipCount,
        int entryCount,
        int txtEntryCount,
        boolean truncated,
        List<String> tree,
        Map<String, Integer> counts,
        int ingestedCount,
        int skippedCount,
        Map<String, Object> trace
) {
}
