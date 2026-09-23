package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChainConfigVectorStoreContractTest {

    @Test
    void missingVectorStoreDoesNotRequestUnavailablePineconeAdapter() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LangChainConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("@ConditionalOnProperty(name = \"vector.store\", havingValue = \"pinecone\", matchIfMissing = true)"));
        assertTrue(source.contains("@ConditionalOnProperty(name = \"vector.store\", havingValue = \"pinecone\", matchIfMissing = false)"));
        assertTrue(source.contains("@Value(\"${vector.store:memory}\") String vectorStoreChoice"));
    }
}
