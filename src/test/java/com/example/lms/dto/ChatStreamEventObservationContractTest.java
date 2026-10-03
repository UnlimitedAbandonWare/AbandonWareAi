package com.example.lms.dto;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChatStreamEventObservationContractTest {
    @AfterEach void cleanup() { TraceStore.clear(); }

    @Test void terminalAndNormalEventsSerializeTheSameReceivedIdentity() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    Map<String, Object> trace = Map.of("observedModel", "main-M", "observedProvider", "fixture",
                            "routeId", "primary", "fallbackCount", 0);
                    trace.forEach(TraceStore::put);
                    ChatResponseDto dto = new ChatResponseDto("synthetic", 1L, "requested-R", false);
                    JsonNode normal = mapper.valueToTree(ChatStreamEvent.done("requested-R", false, 1L)
                            .withObservation(trace));
                    JsonNode terminal = mapper.valueToTree(ChatStreamEvent.terminal(dto));
                    assertEquals("main-M", terminal.path("observedModel").asText());
                    for (String field : new String[]{"observedProvider", "observedModel", "routeId", "fallbackCount"}) {
                        assertEquals(normal.get(field), terminal.get(field), field);
                    }
                });
    }

    @Test void terminalUsesDtoSnapshotEvenAfterTraceChanges() {
        TraceStore.put("observedModel", "main-A");
        ChatResponseDto dto = new ChatResponseDto("synthetic", 1L, "requested-R", false);
        TraceStore.put("observedModel", "later-B");
        ChatStreamEvent event = ChatStreamEvent.terminal(dto);
        assertSame(dto.getObservation(), event.observation());
        assertEquals("main-A", event.observation().observedModel());
    }

    @Test void requestedModelAloneDoesNotBecomeReceivedModel() {
        TraceStore.put("observedReason", "response_not_observed");
        ChatResponseDto dto = new ChatResponseDto("synthetic", 1L, "requested-R", false);
        assertNotNull(ChatStreamEvent.terminal(dto).observation());
        assertNull(ChatStreamEvent.terminal(dto).observation().observedModel());
        assertEquals("response_not_observed", ChatStreamEvent.terminal(dto).observation().observedReason());
    }

    @Test void terminalKeepsTerminationAndObservationTogether() {
        TraceStore.put("observedModel", "main-M");
        var failure = new com.example.lms.llm.gateway.LlmResponseTerminalException("response_incomplete",
                com.example.lms.llm.gateway.LlmFailureClass.UNKNOWN, "partial",
                dev.langchain4j.model.chat.response.ChatResponseMetadata.builder().modelName("main-M").build(),
                "incomplete", "max_output_tokens", null);
        ChatResponseDto dto = ChatResponseDto.terminal(failure, 1L);
        ChatStreamEvent event = ChatStreamEvent.terminal(dto);
        assertSame(dto.getGenerationTermination(), event.generationTermination());
        assertSame(dto.getObservation(), event.observation());
    }
}
