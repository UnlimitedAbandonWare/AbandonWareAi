package com.abandonware.ai.agent.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.codec.ServerSentEvent;
import java.util.Map;




@WebFluxTest(controllers = AgentTraceController.class)
@Import(AgentTraceController.class)
class AgentTraceControllerSseTest {

    @Autowired WebTestClient client;
    @Autowired AgentTraceController controller;

    @Test
    void sse_shouldStreamEvents() {
        var result = client.get().uri("/trace/events")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<Map<String,Object>>>(){});

        controller.publish(Map.of("step","TOOL","tool","web.search"));
        result.getResponseBody().take(1).blockLast();
    }
}