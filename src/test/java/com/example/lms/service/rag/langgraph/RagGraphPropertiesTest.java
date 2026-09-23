package com.example.lms.service.rag.langgraph;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagGraphPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void defaultsKeepLangGraphOff() {
        RagGraphProperties properties = new RagGraphProperties();

        assertEquals(RagGraphProperties.Mode.OFF, properties.getMode());
        assertTrue(properties.isOff());
        assertEquals(12, properties.getMaxSteps());
        assertEquals(15_000L, properties.getTimeoutMs());
        assertEquals("memory", properties.getCheckpoint());
        assertEquals("localhost", properties.getPostgres().getHost());
        assertEquals(5432, properties.getPostgres().getPort());
        assertEquals("lg4j_store", properties.getPostgres().getDatabase());
        assertEquals("lg4j", properties.getPostgres().getUser());
        assertEquals("", properties.getPostgres().getPassword());
        assertFalse(properties.getPostgres().isCreateTables());
        assertFalse(properties.getPostgres().isDropTablesFirst());
    }

    @Test
    void bindsPostgresCheckpointProperties() {
        contextRunner
                .withPropertyValues(
                        "rag.langgraph.checkpoint=postgres",
                        "rag.langgraph.postgres.host=127.0.0.1",
                        "rag.langgraph.postgres.port=15432",
                        "rag.langgraph.postgres.database=lg4j_store_test",
                        "rag.langgraph.postgres.user=lg4j_test",
                        "rag.langgraph.postgres.password=secret",
                        "rag.langgraph.postgres.create-tables=true",
                        "rag.langgraph.postgres.drop-tables-first=true")
                .run(context -> {
                    RagGraphProperties properties = context.getBean(RagGraphProperties.class);

                    assertEquals("postgres", properties.getCheckpoint());
                    assertEquals("127.0.0.1", properties.getPostgres().getHost());
                    assertEquals(15432, properties.getPostgres().getPort());
                    assertEquals("lg4j_store_test", properties.getPostgres().getDatabase());
                    assertEquals("lg4j_test", properties.getPostgres().getUser());
                    assertEquals("secret", properties.getPostgres().getPassword());
                    assertTrue(properties.getPostgres().isCreateTables());
                    assertTrue(properties.getPostgres().isDropTablesFirst());
                });
    }

    @EnableConfigurationProperties(RagGraphProperties.class)
    private static class Config {
    }
}
