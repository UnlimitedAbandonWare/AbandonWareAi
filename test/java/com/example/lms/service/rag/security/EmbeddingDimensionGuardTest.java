package com.example.lms.service.rag.security;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import com.example.lms.client.EmbeddingClient;
import java.lang.reflect.Field;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures that the embedding dimension guard feature flag is configured.
 * <p>
 * The hardening guidelines require that rag.embedding.dimension-guard be
 * enabled to prevent indexing or searching with mismatched embedding
 * dimensions.  This test reads the application.yml file and asserts
 * that the dimension-guard property is present.  It does not attempt
 * to exercise the embedding model itself (which would require
 * instantiating complex services) but instead validates the presence
 * of the configuration key as a proxy for enabling the feature.
 */
public class EmbeddingDimensionGuardTest {

    @Test
    public void applicationConfigContainsDimensionGuard() throws Exception {
        // Load the application.yml from the classpath
        String path = "src/main/resources/application.yml";
        String contents = Files.readString(Paths.get(path));
        assertTrue(contents.contains("dimension-guard"),
                "application.yml should configure rag.embedding.dimension-guard");

        // Validate that the embedding client produces vectors of the configured dimension.
        // Rather than hard-coding the expected size, reflect the static dimension field from
        // the EmbeddingClient to derive the expected length.
        EmbeddingClient client = new EmbeddingClient();
        float[] vector = client.toVector("test");
        Field f = EmbeddingClient.class.getDeclaredField("EMBEDDING_DIMENSION");
        f.setAccessible(true);
        int expected = f.getInt(null);
        assertEquals(expected, vector.length, "EmbeddingClient vector length should match the configured dimension");
    }
}