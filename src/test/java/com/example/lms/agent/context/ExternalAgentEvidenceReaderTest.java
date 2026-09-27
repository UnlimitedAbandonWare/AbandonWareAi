package com.example.lms.agent.context;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalAgentEvidenceReaderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void unreadableExternalEvidenceFilesLeaveRedactedTraceBreadcrumbs(@TempDir Path tempDir) throws Exception {
        Path goalNext = tempDir.resolve("goal-next-auto.summary.json");
        Path noether = tempDir.resolve("noether-subagent-status.json");
        Files.writeString(goalNext, "{not-json");
        Files.writeString(noether, "{not-json");

        Path supabase = tempDir.resolve("supabase-goal-next-auto.summary.json");
        Files.writeString(supabase, "{not-json");

        Map<String, Object> goalRow = ExternalAgentEvidenceReader.goalNextAuto(goalNext.toString());
        Map<String, Object> noetherRow = ExternalAgentEvidenceReader.noether(noether.toString());
        Map<String, Object> supabaseRow = ExternalAgentEvidenceReader.supabaseDetails(supabase.toString());

        assertEquals("goal_next_auto_summary_unreadable", goalRow.get("evidenceNeeded"));
        assertEquals("noether_status_unreadable", noetherRow.get("evidenceNeeded"));
        assertEquals("unknown", supabaseRow.get("mcpDecision"));
        assertEquals(Boolean.TRUE, TraceStore.get("externalEvidence.reader.suppressed.goal_next_auto_summary"));
        assertEquals(Boolean.TRUE, TraceStore.get("externalEvidence.reader.suppressed.noether_status"));
        assertEquals(Boolean.TRUE, TraceStore.get("externalEvidence.reader.suppressed.supabase_details"));
        assertEquals("JsonParseException", TraceStore.get("externalEvidence.reader.suppressed.goal_next_auto_summary.errorType"));
        assertEquals("JsonParseException", TraceStore.get("externalEvidence.reader.suppressed.noether_status.errorType"));
        assertEquals("JsonParseException", TraceStore.get("externalEvidence.reader.suppressed.supabase_details.errorType"));
        assertFalse(TraceStore.getAll().toString().contains(tempDir.toString()));
    }

    @Test
    void lastResortSuppressionBreadcrumbLogsSafeStageWhenTraceStoreWriteFails(@TempDir Path tempDir) throws Exception {
        Path supabase = tempDir.resolve("supabase-goal-next-auto.summary.json");
        Files.writeString(supabase, "{not-json");
        Logger logger = (Logger) LoggerFactory.getLogger(ExternalAgentEvidenceReader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            TraceStore.installContext(new ThrowingTraceMap());

            ExternalAgentEvidenceReader.supabaseDetails(supabase.toString());

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(rendered.contains("[AWX][external-evidence] traceStoreWriteSuppressed"));
            assertTrue(rendered.contains("stage=supabase_details"));
            assertTrue(rendered.contains("errorType=JsonParseException"));
            assertFalse(rendered.contains(tempDir.toString()));
            assertFalse(rendered.contains("ownerToken"));
            assertFalse(rendered.contains("private-token"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            TraceStore.clear();
        }
    }

    @Test
    void invalidGoalNextGeneratedAtLeavesRedactedTraceBreadcrumb(@TempDir Path tempDir) throws Exception {
        Path goalNext = tempDir.resolve("goal-next-auto.summary.json");
        Files.writeString(goalNext, """
                {
                  "ok": true,
                  "decision": "continue",
                  "generatedAt": "ownerToken=private-token"
                }
                """);

        Map<String, Object> goalRow = ExternalAgentEvidenceReader.goalNextAuto(goalNext.toString());

        assertEquals(0, goalRow.get("ageMinutes"));
        assertEquals(Boolean.FALSE, goalRow.get("stale"));
        assertEquals(Boolean.TRUE, TraceStore.get("externalEvidence.reader.suppressed.goal_next_auto_generated_at"));
        assertEquals("DateTimeParseException",
                TraceStore.get("externalEvidence.reader.suppressed.goal_next_auto_generated_at.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("ownerToken"));
        assertFalse(TraceStore.getAll().toString().contains("private-token"));
    }

    private static final class ThrowingTraceMap extends HashMap<String, Object> {
        @Override
        public Object put(String key, Object value) {
            throw new UnsupportedOperationException("ownerToken=private-token");
        }
    }
}
