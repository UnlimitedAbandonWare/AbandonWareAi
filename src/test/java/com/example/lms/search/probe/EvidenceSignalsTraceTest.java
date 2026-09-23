package com.example.lms.search.probe;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSignalsTraceTest {

    @Test
    void evidenceSignalFallbacksLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/probe/EvidenceSignals.java"),
                StandardCharsets.UTF_8);

        for (String expected : List.of(
                "traceSuppressed(\"lane.authority.weightFor\", ignore);",
                "traceSuppressed(\"laneOf.metadata\", ignore);",
                "traceSuppressed(\"contradiction.score\", ignore);",
                "traceSuppressed(\"hash12.sha256\", e);",
                "traceSuppressed(\"firstDouble.parse\", ignore);")) {
            assertTrue(source.contains(expected), expected);
        }
    }
}
