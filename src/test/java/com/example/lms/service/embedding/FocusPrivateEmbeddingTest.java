package com.example.lms.service.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FocusPrivateEmbeddingTest {
    @Test void privateRoutingRemainsLocalWhenGlobalEmbeddingProviderIsOpenAi() {
        var web = mock(WebClient.class);
        var request = mock(WebClient.RequestBodyUriSpec.class, RETURNS_SELF);
        var response = mock(WebClient.ResponseSpec.class);
        when(web.post()).thenReturn(request);
        when(request.retrieve()).thenReturn(response);
        when(response.bodyToMono(String.class)).thenReturn(Mono.just("{\"embeddings\":[[1,2,3,4]]}"));
        var model = new OllamaEmbeddingModel(web);
        ReflectionTestUtils.setField(model,"provider","openai");
        ReflectionTestUtils.setField(model,"model","text-embedding-3-small");
        ReflectionTestUtils.setField(model,"apiUrl","https://remote.invalid/api/embed");
        ReflectionTestUtils.setField(model,"fallbackApiUrl","https://fallback.invalid/api/embed");
        ReflectionTestUtils.setField(model,"dimensions",3);
        assertArrayEquals(new float[]{1,2,3},model.embedPrivate("synthetic fixture").vector());
        verify(request).uri("http://127.0.0.1:11434/api/embed");
        verify(request).bodyValue(Map.of("model","qwen3-embedding:4b","input","synthetic fixture","keep_alive","10m"));
        verify(web,times(1)).post();
        assertTrue(model.privateFingerprint().startsWith("ollama|qwen3-embedding:4b|3|"));
        assertEquals("openai",ReflectionTestUtils.getField(model,"provider"));
    }

    @Test void privateEndpointRejectsRemoteAndCredentialBearingUrls() {
        for (String url : java.util.List.of("https://remote.invalid/api/embed", "http://10.0.0.1/api/embed",
                "http://127.0.0.1/api/embed?fixture=value", "http://user@127.0.0.1/api/embed")) {
            assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(OllamaEmbeddingModel.class,"requirePrivateLoopback",url));
        }
    }
}
