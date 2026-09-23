package com.example.lms.service.vector;

import com.example.lms.config.PineconeProps;
import com.example.lms.search.TraceStore;
import com.example.lms.vector.EmbeddingFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.comparison.*;
import dev.langchain4j.store.embedding.filter.logical.Not;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PineconeVectorStoreAdapterTest {
    @Test void localProfileSelectsCompatiblePipelineDespiteImportedLlmDefaults() {
        for (String profiles : List.of("local", "local,meta-display")) {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                    .withPropertyValues("spring.profiles.active=" + profiles)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var env=context.getEnvironment();
                        assertThat(env.getProperty("embedding.provider")).isEqualTo("openai");
                        assertThat(env.getProperty("embedding.model")).isEqualTo("text-embedding-3-small");
                        assertThat(env.getProperty("embedding.dimensions",Integer.class)).isEqualTo(1536);
                        assertThat(env.getProperty("embedding.fallback.enabled",Boolean.class)).isFalse();
                        assertThat(env.getProperty("vector.store")).isEqualTo("pinecone");
                        assertThat(env.getProperty("pinecone.namespace")).isEqualTo("rag-openai-3-small-v1");
                        assertThat(env.getProperty("vector.upstash.enabled",Boolean.class)).isFalse();
                        assertThat(env.getProperty("upstash.cache.remote-enabled",Boolean.class)).isTrue();
                        assertThat(env.getProperty("upstash.cache.timeout-ms",Long.class)).isEqualTo(500L);
                        assertThat(env.getProperty("logging.level.rag.pipeline")).isEqualTo("DEBUG");
                    });
        }
    }

    final ObjectMapper json = new ObjectMapper();
    final List<Map<String,Object>> bodies = new ArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    String description = "{\"status\":{\"ready\":true},\"dimension\":2,\"metric\":\"cosine\",\"host\":\"fixture.svc.pinecone.io\"}";
    String reply = "{\"matches\":[{\"id\":\"a\",\"score\":0.8,\"metadata\":{\"text_segment\":\"synthetic\",\"sid\":\"A\",\"emb_fp\":\"openai|fixture|2\"}}]}";
    int status = 200;

    EmbeddingFingerprint fingerprint() {
        var fp = new EmbeddingFingerprint();
        ReflectionTestUtils.setField(fp,"provider","openai");
        ReflectionTestUtils.setField(fp,"model","fixture");
        ReflectionTestUtils.setField(fp,"dimensions",2);
        return fp;
    }
    PineconeVectorStoreAdapter adapter() {
        PineconeProps p = new PineconeProps(); p.getApi().setKey("synthetic-credential");
        var index = new PineconeProps.Index(); index.setName("fixture"); p.setIndex(index); p.setNamespace("isolated");
        WebClient http=WebClient.builder().exchangeFunction(req -> {
            calls.incrementAndGet();
            boolean describe=req.url().getHost().equals("api.pinecone.io");
            if (!describe) {
                var capture = new MockClientHttpRequest(req.method(),req.url());
                var context = new BodyInserter.Context() {
                    public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() { return ExchangeStrategies.withDefaults().messageWriters(); }
                    public Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() { return Optional.empty(); }
                    public Map<String,Object> hints() { return Map.of(); }
                };
                req.body().insert(capture,context).block();
                try { bodies.add(json.readValue(capture.getBodyAsString().block(),Map.class)); }
                catch(Exception e) { throw new AssertionError(e); }
            }
            return Mono.just(ClientResponse.create(HttpStatus.valueOf(describe?200:status))
                    .header("Content-Type","application/json").body(describe?description:reply).build());
        }).build();
        return new PineconeVectorStoreAdapter(http,p,fingerprint(),Duration.ofMillis(500));
    }
    EmbeddingSearchRequest query() { return EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1,0}))
            .maxResults(5).minScore(.85).filter(new IsEqualTo("sid","A")).build(); }
    @AfterEach void clear() { TraceStore.clear(); }

    @Test void realRequestPreservesNamespaceFiltersTextAndNormalizedScore() {
        var store=adapter(); var result=store.search(query()); store.search(query());
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).score()).isCloseTo(.9,within(.0001));
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("synthetic");
        assertThat(bodies.get(0)).containsEntry("namespace","isolated").containsEntry("includeValues",false);
        assertThat(bodies.get(0).get("filter").toString()).contains("sid", "A", "emb_fp", "openai|fixture|2");
        assertThat(calls).hasValue(3); // One description for two reads.
        assertThat(TraceStore.get("vector.pinecone.matchCount")).isEqualTo(1);
    }
    @Test void remoteDimensionMismatchNeverReachesQuery() {
        description=description.replace("\"dimension\":2","\"dimension\":3");
        assertThatThrownBy(()->adapter().search(query())).hasMessage("pinecone_dimension_mismatch");
        assertThat(calls).hasValue(1); assertThat(bodies).isEmpty();
    }
    @Test void localDimensionMismatchMakesNoNetworkAttempt() {
        var bad=EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1})).build();
        assertThatThrownBy(()->adapter().search(bad)).hasMessage("pinecone_query_dimension_mismatch");
        assertThat(calls).hasValue(0);
    }
    @Test void mismatchedModelAndOtherTenantAreRejectedEvenIfServerReturnsThem() {
        reply=reply.replace("openai|fixture|2","ollama|other|2");
        assertThat(adapter().search(query()).matches()).isEmpty();
        reply=reply.replace("ollama|other|2","openai|fixture|2").replace("\"sid\":\"A\"","\"sid\":\"B\"");
        assertThat(adapter().search(query()).matches()).isEmpty();
    }
    @Test void unsupportedFilterFailsClosedBeforeNetwork() {
        var bad=EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1,0})).filter(new ContainsString("sid","A")).build();
        assertThatThrownBy(()->adapter().search(bad)).hasMessage("pinecone_unsupported_filter");
        assertThat(calls).hasValue(0);
    }
    @Test void negationAndMembershipAreTranslatedWithoutDroppingConstraints() {
        assertThat(PineconeVectorStoreAdapter.filter(new Not(new IsEqualTo("sid","B")),false))
                .isEqualTo(Map.of("sid",Map.of("$ne","B")));
        var membership=PineconeVectorStoreAdapter.filter(new IsIn("doc_type",List.of("knowledge","faq")),false);
        assertThat(((Collection<?>) ((Map<?,?>)membership.get("doc_type")).get("$in")).stream().map(Object::toString).toList())
                .containsExactlyInAnyOrderElementsOf(List.of("knowledge","faq"));
    }
    @Test void writeFailurePropagatesOnlyReasonCodeAndNeverClaimsMemoryPersistence() {
        status=401; reply="private response and synthetic secret sentinel";
        assertThatThrownBy(()->adapter().add(Embedding.from(new float[]{1,0}),TextSegment.from("synthetic")))
                .hasMessage("pinecone_http_401").hasNoCause();
        assertThat(TraceStore.get("vector.pinecone.disabledReason")).isEqualTo("http_401");
    }
    @Test void upsertStampsModelAndRetainsMetadata() {
        reply="{\"upsertedCount\":1}";
        adapter().addAll(List.of("test-id"),List.of(Embedding.from(new float[]{1,0})),List.of(TextSegment.from("synthetic",Metadata.from("sid","A"))));
        assertThat(bodies.get(0).toString()).contains("text_segment=synthetic","emb_fp=openai|fixture|2","sid=A");
    }
    @Test void missingCredentialsNeverCallControlPlane() {
        var p=new PineconeProps(); var store=new PineconeVectorStoreAdapter(WebClient.builder().exchangeFunction(r->{calls.incrementAndGet();return Mono.error(new AssertionError());}).build(),p,fingerprint(),Duration.ofSeconds(1));
        assertThatThrownBy(()->store.search(query())).hasMessage("pinecone_missing_api_key");
        assertThat(calls).hasValue(0);
    }

    @Test void existingNestedPropertiesStillBindWithoutRenamingKeys() {
        var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
                "pinecone.api.key","synthetic-credential", "pinecone.index.name","fixture", "pinecone.namespace","isolated"));
        var properties=new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("pinecone",org.springframework.boot.context.properties.bind.Bindable.of(PineconeProps.class)).get();
        assertThat(properties.getIndex()).isEqualTo("fixture");
        assertThat(properties.getNamespace()).isEqualTo("isolated");
    }

    @Test void federationRemainsTheOnlyPrimaryInTheProductionBeanGraph() throws Exception {
        var config=com.example.lms.config.LangChainConfig.class;
        var method=Arrays.stream(config.getDeclaredMethods()).filter(m->m.getName().equals("embeddingStore")).findFirst().orElseThrow();
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var composite=org.springframework.beans.factory.support.BeanDefinitionBuilder.genericBeanDefinition(
                dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore.class).getBeanDefinition();
        composite.setPrimary(method.isAnnotationPresent(org.springframework.context.annotation.Primary.class));
        var federated=org.springframework.beans.factory.support.BeanDefinitionBuilder.genericBeanDefinition(
                dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore.class).getBeanDefinition();
        federated.setPrimary(com.example.lms.vector.FederatedEmbeddingStore.class.isAnnotationPresent(org.springframework.context.annotation.Primary.class));
        factory.registerBeanDefinition("embeddingStore",composite);
        factory.registerBeanDefinition("federatedEmbeddingStore",federated);
        assertThat(factory.getBean(dev.langchain4j.store.embedding.EmbeddingStore.class)).isSameAs(factory.getBean("federatedEmbeddingStore"));
    }
}
