package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class UawGeneralPiiPersistenceBoundaryTest {
    @TempDir Path temporary;
    @AfterEach void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> categories() {
        return Stream.of(
                Arguments.of("synthetic_email", "synthetic.person@example.invalid"),
                Arguments.of("synthetic_phone", "+1-202-555-0143"),
                Arguments.of("synthetic_address_like", "999 Example Avenue, Test City"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("categories")
    void currentDirectWriterBoundariesRetainSyntheticCategoryMarkers(String category, String marker)
            throws Exception {
        TraceStore.clear();
        String question = "Synthetic fixture contact: " + marker + ". Explain this placeholder.";
        String answer = "This fictional fixture contains " + marker + " only.";
        var filter = new UawDatasetTrainingDataFilter(new UawDatasetFilterProperties(), null);
        var decision = filter.filter(question, answer, "fixture-model");
        assertTrue(decision.accept(), "real default filter admission");
        assertEquals("ACCEPT_DEFAULT", decision.decisionType().name(), "real filter category");
        var metadata = acceptedMetadata();
        Path dataset = temporary.resolve("dataset.jsonl");
        boolean admitted = new UawDatasetWriter(filter).append(dataset.toFile(), "fixture-dataset",
                question, answer, "fixture-model", 3, "synthetic-session", metadata);
        assertTrue(admitted, "dataset boundary admitted");
        JsonNode row = singleRow(dataset);
        assertEquals(1, occurrences(row.path("question").asText(), marker), "dataset question marker count");
        assertEquals(1, occurrences(row.path("answer").asText(), marker), "dataset answer marker count");
        assertEquals(0, row.path("redactionCount").asInt(-1), "general marker redaction count");
        assertFalse(row.path("redactionApplied").asBoolean(true), "general marker redaction flag");

        Path handoff = temporary.resolve("handoff");
        var properties = new UawAutolearnProperties();
        var cfg = properties.getAgentHandoff();
        cfg.setRootPath(handoff.toString());
        cfg.setAcceptedPath(handoff.resolve("accepted.jsonl").toString());
        cfg.setRejectedPath(handoff.resolve("rejected.jsonl").toString());
        cfg.setCyclePath(handoff.resolve("cycles.jsonl").toString());
        cfg.setManifestPath(handoff.resolve("manifest.json").toString());
        new UawLearningAgentHandoffWriter(properties).recordSample("synthetic-session", "fixture-dataset",
                question, answer, "fixture-model", 3, metadata, admitted);
        JsonNode preview = singleRow(handoff.resolve("accepted.jsonl"));
        assertEquals("ACCEPTED", preview.path("decision").asText());
        assertEquals("ACCEPTED", preview.path("outcome").asText());
        assertEquals(1, occurrences(preview.path("questionPreview").asText(), marker), "handoff question marker count");
        assertEquals(1, occurrences(preview.path("answerPreview").asText(), marker), "handoff answer marker count");
        assertTrue(Files.isRegularFile(handoff.resolve("manifest.json")), "test-local manifest");
        // This is an adverse characterization, not approval to retain personal data.
        System.out.println("AL07_PERSISTENCE category=" + category
                + " filter=ACCEPT_DEFAULT datasetAdmitted=true datasetMarkerCount=2"
                + " handoffOutcome=ACCEPTED handoffMarkerCount=2 datasetRedactionCount=0"
                + " normativePolicyApproved=false wholeUpstreamClaim=false");
    }

    private static JsonNode singleRow(Path path) throws Exception {
        List<String> rows = Files.readAllLines(path).stream().filter(line -> !line.isBlank()).toList();
        assertEquals(1, rows.size(), "single test-local JSONL record");
        return new ObjectMapper().readTree(rows.get(0));
    }

    private static int occurrences(String text, String marker) {
        return (text.length() - text.replace(marker, "").length()) / marker.length();
    }

    private static UawDatasetWriter.TrainingMetadata acceptedMetadata() {
        var validation = new LearningSampleValidationMetadata("causal", List.of("BQ", "ER", "RC"),
                1.0d, 0.74d, 0.30d, 0.78d, new LearningSampleValidationMetadata.Requery(true, true),
                0.0d, 0.0d, 0.82d, List.of(),
                List.of("cause_effect_support", "alternative_cause_checked", "requery_confirmation_required"),
                LearningSampleValidationMetadata.Thresholds.defaults(), LearningSampleValidationMetadata.Runtime.defaults(),
                LearningSampleValidationMetadata.Anomalies.none(), LearningSampleValidationMetadata.Feedback.none(),
                new LearningSampleValidationMetadata.NeedleRoi(true, 0.82d, true, "accepted"));
        return new UawDatasetWriter.TrainingMetadata("uaw_autolearn", "mixed", "", 4, true, 0.75d, validation);
    }
}

