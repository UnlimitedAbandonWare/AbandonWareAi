package com.example.lms.service.rag.kg;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.lms.search.TraceStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Neo4jKnowledgeGraphPropertiesTest {

    @org.junit.jupiter.api.AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void defaultsDisableProviderWithoutThrowing() {
        Neo4jKnowledgeGraphProperties properties = new Neo4jKnowledgeGraphProperties();
        Neo4jKnowledgeGraphClient client = new Neo4jKnowledgeGraphClient(properties);

        assertEquals("disabled", properties.disabledReason());
        assertTrue(client.lookup("GENERAL", Set.of("Alpha"), 3).isEmpty());
        client.upsertFailurePattern("p1", "provider_disabled", "provider",
                "disable_provider_failsoft", 0.80d, 0.90d, 1, 0.10d, 0.90d);
        assertTrue(client.recommendRecovery("p1", "provider_disabled", "provider", 1,
                0.10d, 0.90d, 3).isEmpty());
    }

    @Test
    void unsafeDefaultCredentialsAreNotUsable() {
        Neo4jKnowledgeGraphProperties properties = new Neo4jKnowledgeGraphProperties();
        properties.setEnabled(true);
        properties.setUri("bolt://localhost:7687");
        properties.setUser("neo4j");
        properties.setPassword("neo4j");

        assertEquals("unsafe_default_credentials", properties.disabledReason());
        assertFalse(properties.hasPassword());
    }

    @Test
    void enabledProviderUsesCanonicalDisabledReasons() {
        Neo4jKnowledgeGraphProperties properties = new Neo4jKnowledgeGraphProperties();
        properties.setEnabled(true);

        assertEquals("missing_uri", properties.disabledReason());

        properties.setUri("bolt://localhost:7687");
        assertEquals("missing_user", properties.disabledReason());

        properties.setUser("neo4j");
        assertEquals("missing_password", properties.disabledReason());
    }

    @Test
    void clientReportsMissingPropertiesWithCanonicalReason() {
        Neo4jKnowledgeGraphClient client = new Neo4jKnowledgeGraphClient(null);

        assertEquals("missing_properties", client.disabledReason());
    }

    @Test
    void clientFailureClassNormalizesCancellationWithoutLeakingMessage() {
        String failureClass = ReflectionTestUtils.invokeMethod(
                Neo4jKnowledgeGraphClient.class,
                "failureClass",
                new CancellationException("cancelled ownerToken fake-token"));

        assertEquals("cancelled", failureClass);
        assertFalse(failureClass.contains("CancellationException"));
        assertFalse(failureClass.contains("ownerToken"));
    }

    @Test
    void lookupFailureReturnsEmptyAndRecordsFailSoftTrace() {
        Neo4jKnowledgeGraphProperties properties = new Neo4jKnowledgeGraphProperties();
        properties.setEnabled(true);
        properties.setUri("bolt://localhost:7687");
        properties.setUser("neo4j");
        properties.setPassword("correct-horse-battery-staple");
        properties.setDatabase("");
        Neo4jKnowledgeGraphClient client = new Neo4jKnowledgeGraphClient(properties);
        Driver driver = mock(Driver.class);
        when(driver.session()).thenThrow(new RuntimeException("ownerToken raw-secret"));
        ReflectionTestUtils.setField(client, "driver", driver);

        assertDoesNotThrow(() -> assertTrue(client.lookup("GENERAL", Set.of("Alpha"), 3).isEmpty()));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.neo4j.failed"));
        assertEquals("RuntimeException", TraceStore.get("retrieval.kg.neo4j.failureClass"));
        assertEquals("empty_list", TraceStore.get("retrieval.kg.neo4j.fallback"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-secret"));
    }

    @Test
    void failurePatternUpsertFailureRecordsFailSoftTraceWithoutRawMessage() {
        Neo4jKnowledgeGraphProperties properties = new Neo4jKnowledgeGraphProperties();
        properties.setEnabled(true);
        properties.setUri("bolt://localhost:7687");
        properties.setUser("neo4j");
        properties.setPassword("correct-horse-battery-staple");
        properties.setDatabase("");
        Neo4jKnowledgeGraphClient client = new Neo4jKnowledgeGraphClient(properties);
        Driver driver = mock(Driver.class);
        when(driver.session()).thenThrow(new RuntimeException("ownerToken raw-upsert-secret"));
        ReflectionTestUtils.setField(client, "driver", driver);

        assertDoesNotThrow(() -> client.upsertFailurePattern("p1", "timeout", "provider",
                "retry_later", 0.8d, 0.7d, 3, 0.2d, 0.4d));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.neo4j.upsert.failed"));
        assertEquals("RuntimeException", TraceStore.get("retrieval.kg.neo4j.upsert.failureClass"));
        assertEquals("skip_write", TraceStore.get("retrieval.kg.neo4j.upsert.fallback"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-upsert-secret"));
    }

    @Test
    void failurePatternRecommendFailureRecordsFailSoftTraceWithoutRawMessage() {
        Neo4jKnowledgeGraphProperties properties = new Neo4jKnowledgeGraphProperties();
        properties.setEnabled(true);
        properties.setUri("bolt://localhost:7687");
        properties.setUser("neo4j");
        properties.setPassword("correct-horse-battery-staple");
        properties.setDatabase("");
        Neo4jKnowledgeGraphClient client = new Neo4jKnowledgeGraphClient(properties);
        Driver driver = mock(Driver.class);
        when(driver.session()).thenThrow(new RuntimeException("ownerToken raw-recommend-secret"));
        ReflectionTestUtils.setField(client, "driver", driver);

        assertTrue(client.recommendRecovery("p1", "timeout", "provider", 3,
                0.2d, 0.4d, 2).isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.neo4j.recommend.failed"));
        assertEquals("RuntimeException", TraceStore.get("retrieval.kg.neo4j.recommend.failureClass"));
        assertEquals("empty_list", TraceStore.get("retrieval.kg.neo4j.recommend.fallback"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-recommend-secret"));
    }

    @Test
    void clientFailureLogsUseCanonicalReasonTaxonomy() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/kg/Neo4jKnowledgeGraphClient.java"));

        assertTrue(source.contains("neo4j_query_failed"));
        assertTrue(source.contains("neo4j_upsert_failed"));
        assertTrue(source.contains("neo4j_recommend_failed"));
        assertFalse(source.contains("disabledReason=query failed"));
        assertFalse(source.contains("disabledReason=upsert failed"));
        assertFalse(source.contains("disabledReason=recommend failed"));
    }

    @Test
    void safeCredentialsExposeOnlyEndpointHost() {
        Neo4jKnowledgeGraphProperties properties = new Neo4jKnowledgeGraphProperties();
        properties.setEnabled(true);
        properties.setUri("bolt://localhost:7687");
        properties.setUser("neo4j");
        properties.setPassword("correct-horse-battery-staple");

        assertNull(properties.disabledReason());
        assertTrue(properties.hasPassword());
        assertEquals("localhost", properties.endpointHost());
    }

    @Test
    void endpointHostParseFailureLeavesTraceBreadcrumbWithoutRawUri() {
        Neo4jKnowledgeGraphProperties properties = new Neo4jKnowledgeGraphProperties();
        properties.setUri("://ownerToken=raw-neo4j-uri");

        assertEquals("", properties.endpointHost());
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.neo4j.endpointHost.suppressed"));
        assertEquals("parse-failure", TraceStore.get("retrieval.kg.neo4j.endpointHost.reason"));
        assertEquals("IllegalArgumentException", TraceStore.get("retrieval.kg.neo4j.endpointHost.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-neo4j-uri"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void failurePatternGraphDoesNotClaimRecoverySuccessEdge() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/kg/Neo4jKnowledgeGraphClient.java"));

        assertFalse(source.contains("RECOVERED_BY"));
        assertTrue(source.contains("actionableRecommendation"));
    }

    @Test
    void lookupQueryReadsLegacyAndBrainStateRelationshipTypes() {
        assertTrue(Neo4jKnowledgeGraphClient.LOOKUP_RELATION_QUERY.contains(":KG_REL|RELATED_TO"));
    }

    @Test
    void neo4jParseFallbacksLeaveStageOnlyBreadcrumbs() throws Exception {
        String client = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/kg/Neo4jKnowledgeGraphClient.java"));
        String properties = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/kg/Neo4jKnowledgeGraphProperties.java"));

        assertTrue(client.contains("stage=safe_string err=parse-failure"));
        assertTrue(client.contains("stage=safe_double err=parse-failure"));
        assertTrue(client.contains("stage=safe_instant err=parse-failure"));
        assertFalse(client.contains("stage=safe_instant errorType={}"));
        assertFalse(client.contains("ex.getClass().getSimpleName()"));
        assertTrue(properties.contains("stage=endpoint_host err=parse-failure"));
    }

    @Test
    void clientValueParseFallbacksLeaveTraceBreadcrumbsWithoutRawValues() {
        Record record = mock(Record.class);
        Value value = mock(Value.class);
        when(record.get("entity")).thenReturn(value);
        when(value.asString("")).thenThrow(new IllegalArgumentException("ownerToken raw-kg-value"));
        when(value.asDouble(0.25d)).thenThrow(new IllegalArgumentException("ownerToken raw-kg-double"));

        assertEquals("", ReflectionTestUtils.invokeMethod(
                Neo4jKnowledgeGraphClient.class, "safeString", record, "entity"));
        assertEquals(0.25d, (Double) ReflectionTestUtils.invokeMethod(
                Neo4jKnowledgeGraphClient.class, "safeDouble", record, "entity", 0.25d), 0.0001d);

        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.neo4j.valueParse.suppressed.safe_string"));
        assertEquals("parse-failure", TraceStore.get("retrieval.kg.neo4j.valueParse.safe_string.reason"));
        assertEquals("parse_failure", TraceStore.get("retrieval.kg.neo4j.valueParse.safe_string.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.neo4j.valueParse.suppressed.safe_double"));
        assertEquals("parse-failure", TraceStore.get("retrieval.kg.neo4j.valueParse.safe_double.reason"));
        assertEquals("parse_failure", TraceStore.get("retrieval.kg.neo4j.valueParse.safe_double.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-kg-value"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-kg-double"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void applicationYamlKeepsCanonicalRetrievalNeo4jEnvAliases() throws Exception {
        String yaml = Files.readString(Path.of("main/resources/application.yml"));

        assertTrue(yaml.contains("enabled: ${RETRIEVAL_KG_NEO4J_ENABLED:false}"));
        assertTrue(yaml.contains("uri: ${RETRIEVAL_KG_NEO4J_URI:${NEO4J_URI:}}"));
        assertTrue(yaml.contains("user: ${RETRIEVAL_KG_NEO4J_USER:${NEO4J_USER:}}"));
        assertTrue(yaml.contains("password: ${RETRIEVAL_KG_NEO4J_PASSWORD:${NEO4J_PASSWORD:}}"));
        assertTrue(yaml.contains("database: ${RETRIEVAL_KG_NEO4J_DATABASE:${NEO4J_DATABASE:neo4j}}"));
        assertTrue(yaml.contains("timeout-ms: ${RETRIEVAL_KG_NEO4J_TIMEOUT_MS:${NEO4J_TIMEOUT_MS:1200}}"));
    }
}
