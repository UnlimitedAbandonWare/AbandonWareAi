package com.example.lms.service;

import com.example.lms.repository.CurrentModelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GPTServiceSecurityTest {

    @Test
    void placeholderApiKeyFailsBeforeOutboundCompletionCall() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"model\":\"gpt-test\",\"choices\":[{\"message\":{\"content\":\"remote\"}}]}")
                            .build());
                })
                .build();
        GPTService service = new GPTService(client, mock(CurrentModelRepository.class));
        ReflectionTestUtils.setField(service, "apiUrl", "https://api.openai.com/v1");
        ReflectionTestUtils.setField(service, "apiKey", "sk-local");
        ReflectionTestUtils.setField(service, "defaultModelFromProps", "gpt-test");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.chatCompletion("hello", "gpt-test"));

        assertTrue(ex.getMessage().contains("OpenAI API key is missing"));
        assertEquals(0, calls.get());
    }
}
