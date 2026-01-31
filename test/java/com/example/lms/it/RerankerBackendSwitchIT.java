package com.example.lms.it;

import com.example.lms.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;



/**
 * Smoke test to ensure the application context loads when the reranker backend
 * is set to the embedding model. This verifies that dependency injection
 * succeeds and the ChatService is available.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "abandonware.reranker.backend=embedding-model"
})
class RerankerBackendSwitchIT {
    @Autowired
    ChatService chat;

    @Test
    void contextLoads_withEmbeddingBackend() {
        // simply accessing a method ensures the bean is created
        chat.toString();
    }
}