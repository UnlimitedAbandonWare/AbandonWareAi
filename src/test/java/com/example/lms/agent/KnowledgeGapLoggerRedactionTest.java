package com.example.lms.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGapLoggerRedactionTest {

    @Test
    void gapEventJsonExposesOnlyFingerprintsForSensitiveText() throws Exception {
        KnowledgeGapLogger.GapEvent event = new KnowledgeGapLogger.GapEvent(
                "private question sk-local-secret",
                "private-domain.example",
                "sensitive subject",
                "sensitive intent");

        String json = new ObjectMapper().writeValueAsString(event);

        assertFalse(json.contains("private question"));
        assertFalse(json.contains("sk-local-secret"));
        assertFalse(json.contains("private-domain.example"));
        assertFalse(json.contains("sensitive subject"));
        assertFalse(json.contains("sensitive intent"));

        assertTrue(json.contains("\"queryHash\""));
        assertTrue(json.contains("\"queryLength\""));
        assertTrue(json.contains("\"domainHash\""));
        assertTrue(json.contains("\"domainLength\""));
        assertTrue(json.contains("\"subjectHash\""));
        assertTrue(json.contains("\"subjectLength\""));
        assertTrue(json.contains("\"intentHash\""));
        assertTrue(json.contains("\"intentLength\""));
    }
}
