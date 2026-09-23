package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowSectionMarkerTest {

    @Test
    void chatWorkflowKeepsCommentOnlyExtractionRoadmapMarkers() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("[SECTION 1] SSE/request orchestration"));
        assertTrue(source.contains("[SECTION 2] Prompt context assembly"));
        assertTrue(source.contains("[SECTION 3] Retrieval/evidence processing"));
        assertTrue(source.contains("[SECTION 4] Quality gate and final answer chain"));
        assertTrue(source.contains("Comment-only roadmap; no extraction in this safe patch."));
    }
}
