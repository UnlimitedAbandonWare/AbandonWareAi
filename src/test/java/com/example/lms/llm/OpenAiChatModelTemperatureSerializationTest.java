package com.example.lms.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatModelTemperatureSerializationTest {
    private JsonNode request(String endpoint, String id, boolean defaults) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        WebClient client = WebClient.builder().clientConnector((method, uri, callback) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            return callback.apply(request).then(Mono.defer(() -> request.getBodyAsString().map(json -> {
                body.set(json);
                MockClientHttpResponse response = new MockClientHttpResponse(HttpStatus.OK);
                response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                response.setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
                return response;
            })));
        }).build();
        OpenAiChatModel model = new OpenAiChatModel(client);
        ReflectionTestUtils.setField(model, "baseUrl", endpoint);
        ReflectionTestUtils.setField(model, "defaultModel", id);
        ReflectionTestUtils.setField(model, "apiKey", "synthetic-offline-credential");
        model.normaliseAndValidate();
        assertEquals("ok", defaults ? model.generate("synthetic") : model.generate("synthetic", 0.2, 123));
        assertNotNull(body.get(), "real WebClient body serialization must run");
        return new ObjectMapper().readTree(body.get());
    }

    @ParameterizedTest
    @CsvSource({"gpt-5", "gpt-5-mini", "gpt-5-nano"})
    void originalModelsOmitTemperature(String model) throws Exception {
        assertFalse(request("https://api.openai.com/v1/", model, false).has("temperature"));
    }

    @Test void defaultOverloadAlsoOmitsTemperature() throws Exception {
        assertFalse(request("https://API.OPENAI.COM", "gpt-5", true).has("temperature"));
    }

    @ParameterizedTest
    @CsvSource({
        "https://api.openai.com,gpt-4o-mini", "https://api.openai.com,gpt-5.1",
        "https://api.openai.com,gpt-5.2", "https://api.openai.com,gpt-5-chat-latest",
        "https://api.openai.com,gpt-5-mini-custom", "http://localhost:11434,gpt-5",
        "https://gateway.invalid,gpt-5-mini", "https://api.openai.com.gateway.invalid,gpt-5",
        "https://gateway.invalid/api.openai.com,gpt-5", "https://gateway.invalid?api.openai.com,gpt-5"
    })
    void compatibleRoutesRetainCallerTemperature(String endpoint, String model) throws Exception {
        assertEquals(0.2, request(endpoint, model, false).path("temperature").asDouble());
    }
}
