package com.example.lms.graphdb;

import com.example.lms.file.FileIngestionService;
import com.example.lms.service.rag.graph.GraphRagChunkingService;
import com.example.lms.service.rag.graph.Neo4jKgChunkWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphDbManualLearningContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    GraphDbManualLearningConfiguration.class,
                    GraphDbManualLearningController.class,
                    GraphDbManualLearningService.class,
                    GraphDbClient.class,
                    TestDependencies.class);

    @Test
    void contextBindsPropertiesAndAssemblesManualLearningRoute() {
        contextRunner
                .withPropertyValues(
                        "graphdb.manual-learning.enabled=true",
                        "graphdb.manual-learning.dry-run-default=false",
                        "graphdb.manual-learning.vector-enabled=true",
                        "graphdb.manual-learning.neo4j-enabled=true",
                        "graphdb.manual-learning.brain-state-mirror-enabled=true",
                        "graphdb.manual-learning.max-text-chars=250000")
                .run(context -> {
                    assertThat(context).hasSingleBean(GraphDbManualLearningProperties.class);
                    assertThat(context).hasSingleBean(GraphDbManualLearningController.class);
                    assertThat(context).hasSingleBean(GraphDbManualLearningService.class);
                    assertThat(context).hasSingleBean(GraphDbClient.class);

                    GraphDbManualLearningProperties properties =
                            context.getBean(GraphDbManualLearningProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.isDryRunDefault()).isFalse();
                    assertThat(properties.isVectorEnabled()).isTrue();
                    assertThat(properties.isNeo4jEnabled()).isTrue();
                    assertThat(properties.isBrainStateMirrorEnabled()).isTrue();
                    assertThat(properties.getMaxTextChars()).isEqualTo(250_000);

                    var response = context.getBean(GraphDbManualLearningController.class).status();
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    GraphDbManualLearningService.LearnReport body = response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.status()).isEqualTo("ready_unverified");
                    assertThat(body.disabledReason()).isEqualTo("non_dry_run_live_proof_required");
                    assertThat(body.manifest()).containsEntry("lane", "graphdb_manual_learning");
                    assertThat(body.manifest()).containsEntry("featureFlagProperty", "graphdb.manual-learning.enabled");
                    assertThat(body.manifest()).containsEntry("writeBoundary", "graphdb_manual_learning");
                    assertThat(body.manifest()).containsEntry("readBoundary", "graphdb_manual_learning");
                    assertThat(body.manifest()).containsEntry("authGuardBoundary", "AdminTokenGuardFilter+ROLE_ADMIN");
                    assertThat(body.manifest()).containsEntry("authTokenValuesIncluded", false);
                    assertThat(body.manifest()).containsEntry("brainStateMirrorRequested", true);
                    assertThat(body.manifest()).containsEntry("brainStateMirrorEnabled", false);
                    assertThat(body.manifest()).containsEntry(
                            "brainStateMirrorSuppressedReason",
                            "graphdb_manual_lane_excludes_brain_state");
                    assertThat(body.manifest()).containsEntry("queryTimeRetrievalCoupled", false);
                    assertThat(body.manifest()).containsEntry("queryTimeAnchorMapCoupled", false);
                    assertThat(body.manifest()).containsEntry("rawSecretsIncluded", false);
                    assertThat(body.manifest()).containsEntry("nonDryRunLiveProofStatus", "required_unverified");
                });
    }

    @Test
    void defaultsKeepRouteDisabledDryRunAndClampTextLimit() {
        contextRunner
                .withPropertyValues("graphdb.manual-learning.max-text-chars=42")
                .run(context -> {
                    GraphDbManualLearningProperties properties =
                            context.getBean(GraphDbManualLearningProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.isDryRunDefault()).isTrue();
                    assertThat(properties.getMaxTextChars()).isEqualTo(1_000);

                    GraphDbManualLearningService.LearnReport status =
                            context.getBean(GraphDbManualLearningService.class).status();
                    assertThat(status.status()).isEqualTo("disabled");
                    assertThat(status.disabledReason()).isEqualTo("route_disabled");
                    assertThat(status.manifest()).containsEntry("routeStatus", "disabled");
                    assertThat(status.manifest()).containsEntry("routeDisabledReason", "route_disabled");
                    assertThat(status.manifest()).containsEntry("rawTextIncluded", false);
                    assertThat(status.manifest()).containsEntry("rawIdentifiersIncluded", false);
                    assertThat(status.manifest()).containsEntry("authTokenValuesIncluded", false);
                });
    }

    @Test
    void applicationYamlKeepsGraphDbManualLearningEnvAliasesAndCanonicalNeo4jNamespace() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertYamlValue(sources, "graphdb.manual-learning.enabled", "${GRAPHDB_MANUAL_LEARNING_ENABLED:false}");
        assertYamlValue(sources, "graphdb.manual-learning.dry-run-default",
                "${GRAPHDB_MANUAL_LEARNING_DRY_RUN_DEFAULT:true}");
        assertYamlValue(sources, "graphdb.manual-learning.vector-enabled",
                "${GRAPHDB_MANUAL_LEARNING_VECTOR_ENABLED:true}");
        assertYamlValue(sources, "graphdb.manual-learning.neo4j-enabled",
                "${GRAPHDB_MANUAL_LEARNING_NEO4J_ENABLED:true}");
        assertYamlValue(sources, "graphdb.manual-learning.brain-state-mirror-enabled",
                "${GRAPHDB_MANUAL_LEARNING_BRAIN_STATE_MIRROR_ENABLED:false}");
        assertYamlValue(sources, "graphdb.manual-learning.max-text-chars",
                "${GRAPHDB_MANUAL_LEARNING_MAX_TEXT_CHARS:50000}");

        assertYamlValue(sources, "retrieval.kg.neo4j.enabled", "${RETRIEVAL_KG_NEO4J_ENABLED:false}");
        assertYamlValue(sources, "retrieval.kg.neo4j.uri", "${RETRIEVAL_KG_NEO4J_URI:${NEO4J_URI:}}");
        assertYamlValue(sources, "retrieval.kg.neo4j.user", "${RETRIEVAL_KG_NEO4J_USER:${NEO4J_USER:}}");
        assertYamlValue(sources, "retrieval.kg.neo4j.password",
                "${RETRIEVAL_KG_NEO4J_PASSWORD:${NEO4J_PASSWORD:}}");
        assertYamlValue(sources, "retrieval.kg.neo4j.database",
                "${RETRIEVAL_KG_NEO4J_DATABASE:${NEO4J_DATABASE:neo4j}}");
        assertYamlValue(sources, "retrieval.kg.neo4j.timeout-ms",
                "${RETRIEVAL_KG_NEO4J_TIMEOUT_MS:${NEO4J_TIMEOUT_MS:1200}}");
    }

    private static void assertYamlValue(List<PropertySource<?>> sources, String key, String expected) {
        assertThat(sources)
                .anySatisfy(source -> assertThat(source.getProperty(key)).isEqualTo(expected));
    }

    @Configuration(proxyBeanMethods = false)
    private static class TestDependencies {

        @Bean
        GraphRagChunkingService graphRagChunkingService() {
            return mock(GraphRagChunkingService.class);
        }

        @Bean
        Neo4jKgChunkWriter neo4jKgChunkWriter() {
            Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
            when(writer.status()).thenReturn(Map.of(
                    "backend", "neo4j",
                    "enabled", true,
                    "status", "ready",
                    "disabledReason", "",
                    "endpointHost", "localhost"));
            return writer;
        }

        @Bean
        FileIngestionService fileIngestionService() {
            return mock(FileIngestionService.class);
        }
    }
}
