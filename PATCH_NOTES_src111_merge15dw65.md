# PATCH_NOTES — src111_merge15dw65

Date: 2025-10-12

This patch fills the delta between the resume-claimed features and the repository implementation.

## What changed (High → Low priority)

1) **Kafka integration**
- Added dependency: `org.springframework.kafka:spring-kafka` (app, lms-core)
- New files:
  - `src/main/java/infra/kafka/KafkaConfig.java`
  - `src/main/java/infra/kafka/KafkaTopics.java`
  - `src/main/java/telemetry/TelemetryProducer.java`
  - `src/main/java/telemetry/InferenceLogConsumer.java`
- New config in `application.yml` → `features.kafka` + `spring.kafka.*` + `app.kafka.topics.*`

2) **Qdrant adapter (REST)**
- Added dependency: `org.springframework:spring-webflux`
- New files under `com.abandonware.ai.vector.qdrant`:
  - `QdrantProperties.java` (binds `qdrant.*`)
  - `QdrantClient.java` (REST client using `RestClient`)
  - `QdrantVectorStoreAdapter.java` (bean; ready to route into FederatedEmbeddingStore)
- New config in `application.yml` → `qdrant.*`

3) **OpenTelemetry (OTLP exporter + Micrometer registry)**
- Added deps: `io.opentelemetry:opentelemetry-sdk`, `opentelemetry-exporter-otlp`, `io.micrometer:micrometer-registry-otlp`, `io.micrometer:micrometer-tracing-bridge-otel`
- New files:
  - `src/main/java/otel/TelemetryConfig.java`
  - `src/main/java/otel/TelemetryProps.java`
- New config in `application.yml` → `features.otel` + `otel.exporter.otlp.endpoint`

4) **Lucene Nori + Synonyms for local BM25**
- Added deps: `org.apache.lucene:lucene-analysis-nori`, `lucene-analyzers-common`
- New files:
  - `src/main/java/search/lucene/LuceneAnalyzerConfig.java`
  - `src/main/resources/analysis/synonyms_ko.txt`
- New config in `application.yml` → `features.lucene.korean`, `features.lucene.synonyms-enabled`

5) **LangChain4j version pinned to 1.0.1**
- New `gradle.properties` with `systemProp.langchain.version=1.0.1` (used by lms-core build)
- (Optional) run `./gradlew dependencies --write-locks` in CI to lock all configurations.

## Non-invasive design
- All new beans are guarded via feature flags (`features.kafka`, `features.otel`, `features.lucene.korean`) or the `qdrant.enabled` switch.
- No existing classes were modified; the patch is pure-additive + configuration append to avoid conflicts.

## Follow-up (optional)
- Wire `QdrantVectorStoreAdapter` into the existing FederatedEmbeddingStore routing table if not already auto-detected.
- Publish Soak/Probe / RAG step logs through `TelemetryProducer`.
- If the environment already has `@ConfigurationPropertiesScan`, you may remove `@Component` from `*Props` classes.
