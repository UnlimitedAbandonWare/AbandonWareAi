package com.example.lms.dto;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.CitationGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChatMissingEvidenceHintMetadataTest {
    @AfterEach void clear() { TraceStore.clear(); }

    private static Map<String, Object> capturedEmptyEvidence() {
        new CitationGate(false).decide(List.of(), 1, 1.0);
        var captured = new LinkedHashMap<String, Object>(TraceStore.getAll());
        captured.put("raw.query", "synthetic-only-private-text");
        captured.put("snippet", "synthetic-only-private-text");
        captured.put("http.header", "synthetic-only-private-text");
        TraceStore.clear();
        return captured;
    }

    @Test void finalSseCarriesOnlyTheFixedHintAfterTraceWasCleared() {
        var captured = capturedEmptyEvidence();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    JsonNode json = mapper.valueToTree(ChatStreamEvent.done("fixture-model", false, 1L)
                            .withObservation(captured));
                    assertEquals("근거 없음", json.path("evidenceHint").asText());
                    assertFalse(json.toString().contains("synthetic-only-private-text"));
                    assertEquals("fixture-model", json.path("modelUsed").asText());
                });
    }

    @Test void syncDtoUsesCapturedMetadataAfterTraceWasCleared() throws Exception {
        var captured = capturedEmptyEvidence();
        var constructor = ChatResponseDto.class.getConstructor(String.class, Long.class, String.class,
                boolean.class, String.class, Long.class, LearningContextMetadata.class, List.class,
                ChatStreamEvent.PipelineSnapshot.class,
                com.example.lms.infra.selection.SelectionEntropyProjection.class, Map.class);
        ChatResponseDto dto = constructor.newInstance("synthetic answer", 1L, "fixture-model",
                false, null, null, LearningContextMetadata.empty(), List.of(), null, null, captured);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    JsonNode json = mapper.valueToTree(dto);
                    assertEquals("근거 없음", json.path("evidenceHint").asText());
                    assertEquals("synthetic answer", json.path("content").asText());
                    assertFalse(json.toString().contains("synthetic-only-private-text"));
                });
    }

    @Test void absentAndUnrecognizedHintsAreOmitted() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    for (Map<String, Object> trace : List.<Map<String, Object>>of(Map.of(),
                            Map.of("guard.citation.hint", Map.of("status", "synthetic-only-private-text")),
                            Map.of("guard.citation.hint", "근거 없음"))) {
                        JsonNode json = mapper.valueToTree(ChatStreamEvent.done("fixture-model", false, 1L)
                                .withObservation(trace));
                        assertFalse(json.has("evidenceHint"));
                        assertFalse(json.toString().contains("synthetic-only-private-text"));
                    }
                    assertFalse(mapper.valueToTree(new ChatResponseDto("answer", 1L,
                            "fixture-model", false)).has("evidenceHint"));
                });
    }
}

