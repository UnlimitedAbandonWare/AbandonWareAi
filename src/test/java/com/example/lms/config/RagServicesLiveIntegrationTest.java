package com.example.lms.config;

import com.example.lms.infra.upstash.*;
import com.example.lms.prompt.*;
import com.example.lms.search.TraceStore;
import com.example.lms.service.embedding.OllamaEmbeddingModel;
import com.example.lms.service.rag.*;
import com.example.lms.vector.*;
import com.example.lms.vector.config.FederatedVectorStorePatchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in, bounded live proof using only public synthetic content and owned IDs. */
@EnabledIfEnvironmentVariable(named="AWX_RAG_SERVICES_LIVE", matches="true")
class RagServicesLiveIntegrationTest {
    private static <T> ObjectProvider<T> provider(T value) {
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        if(value!=null) factory.registerSingleton("fixture",value);
        return new ObjectProvider<>() {
            public T getObject(Object... args) { return value; }
            public T getObject() { return value; }
            public T getIfAvailable() { return value; }
            public T getIfUnique() { return value; }
            public Iterator<T> iterator() { return value==null?Collections.emptyIterator():List.of(value).iterator(); }
        };
    }

    @Test void embeddingFederationPineconeRedisPromptAndFinalResponse() throws Exception {
        Path evidence=Path.of("data/agent-handoff/rag-services-20260916");
        ObjectMapper json=new ObjectMapper();
        var synthetic=json.readTree(evidence.resolve("synthetic-embeddings.json").toFile());
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("scope","production embedding/composite/federation/retriever/cache/prompt classes; synthetic documents; one final OpenAI response");
        var fp=new EmbeddingFingerprint();
        ReflectionTestUtils.setField(fp,"provider","openai");
        ReflectionTestUtils.setField(fp,"model","text-embedding-3-small");
        ReflectionTestUtils.setField(fp,"dimensions",1536);
        ReflectionTestUtils.setField(fp,"enabled",true);
        var config=new LangChainConfig(null,null);
        ReflectionTestUtils.setField(config,"embeddingProvider","openai");
        ReflectionTestUtils.setField(config,"embeddingModelName","text-embedding-3-small");
        ReflectionTestUtils.setField(config,"embeddingDimensions",1536);
        ReflectionTestUtils.setField(config,"openAiKey",System.getenv("OPENAI_API_KEY"));
        ReflectionTestUtils.setField(config,"openAiTimeoutSec",20L);
        var embedding=config.embeddingModel(mock(OllamaEmbeddingModel.class),fp);
        var props=new PineconeProps(); props.getApi().setKey(System.getenv("PINECONE_API_KEY"));
        var index=new PineconeProps.Index();index.setName("brilliant-aspen");props.setIndex(index);
        props.setNamespace("rag-openai-3-small-v1");
        var http=WebClient.builder().codecs(c->c.defaultCodecs().maxInMemorySize(2*1024*1024)).build();
        var pinecone=config.pineconeEmbeddingStore(props,fp,http,3000);
        var composite=config.embeddingStore(null,provider(pinecone),fp,null,"pinecone");
        var list=new FederatedVectorStorePatchConfig().federatedEmbeddingStores(provider(composite),provider(pinecone),provider(null));
        var federation=new FederatedEmbeddingStore(provider(list),new TopicRoutingSettings(Map.of(),1),5000,1);
        var rag=new LangChainRAGService(embedding,federation);
        var redis=new UpstashRedisClient(WebClient.builder());
        ReflectionTestUtils.setField(redis,"url",System.getenv("UPSTASH_REDIS_REST_URL"));
        ReflectionTestUtils.setField(redis,"token",System.getenv("UPSTASH_REDIS_REST_TOKEN"));
        var l1=Caffeine.newBuilder().<String,String>build();
        var cache=new UpstashBackedWebCache(l1,redis);
        ReflectionTestUtils.setField(cache,"timeoutMs",500L);
        String sid="awx-proof-"+UUID.randomUUID();
        String cacheKey="awx:rag-proof:"+UUID.randomUUID();
        List<String> ids=new ArrayList<>();
        List<Embedding> vectors=new ArrayList<>();
        List<TextSegment> segments=new ArrayList<>();
        for(int i=0;i<8;i++) {
            ids.add(sid+"-"+i);
            float[] values=json.convertValue(synthetic.path("vectors").get(i),float[].class);
            vectors.add(Embedding.from(values));
            segments.add(TextSegment.from(synthetic.path("docs").get(i).asText(),Metadata.from(Map.of("sid",sid,"doc_type","KB","strict_write","true"))));
        }
        Files.writeString(evidence.resolve("live-owned-targets.json"),json.writeValueAsString(Map.of(
                "index","brilliant-aspen","namespace",props.getNamespace(),"ids",ids,"sid",sid,"redisKey",cacheKey,"redisTtlSeconds",60)));
        try {
            long began=System.nanoTime();
            federation.addAll(ids,vectors,segments);
            report.put("upsertCount",ids.size());
            report.put("upsertMs",(System.nanoTime()-began)/1_000_000);
            String question=synthetic.path("queries").get(0).asText();
            var query=QueryUtils.buildQuery(question,Map.of("sid",sid,"vecTopK",3,"vectorMinScore",.4));
            List<Content> retrieved=List.of();
            int searches=0;
            for(;searches<5;searches++) {
                retrieved=rag.asContentRetriever("brilliant-aspen").retrieve(query);
                if(!retrieved.isEmpty()) break;
                Thread.sleep(1000);
            }
            assertFalse(retrieved.isEmpty(),"retrieval must return real stored synthetic evidence");
            report.put("retrievalCount",retrieved.size());
            report.put("consistencySearchAttempts",searches+1);
            report.put("coldEmbeddingMs",TraceStore.get("rag.pipeline.embedding.latencyMs"));
            report.put("coldVectorMs",TraceStore.get("rag.pipeline.vector.latencyMs"));
            report.put("coldRetrievalMs",TraceStore.get("rag.pipeline.retrieval.latencyMs"));
            var second=rag.asContentRetriever("brilliant-aspen").retrieve(query);
            assertFalse(second.isEmpty());
            report.put("embeddingCacheHits",TraceStore.get("embeddingCache.hit.count"));
            report.put("embeddingCacheMisses",TraceStore.get("embeddingCache.miss.count"));
            var other=EmbeddingSearchRequest.builder().queryEmbedding(vectors.get(0)).filter(new IsEqualTo("sid",sid+"-other")).maxResults(3).build();
            assertTrue(pinecone.search(other).matches().isEmpty(),"cross-session negative must remain empty");
            report.put("crossSessionIsolation",true);
            assertTrue(cache.get(cacheKey).block(Duration.ofSeconds(2)).isEmpty());
            assertEquals("miss",TraceStore.get("web.cache.result"));
            cache.put(cacheKey,"Synthetic web evidence: equal vector dimensions do not prove embedding-model compatibility.",Duration.ofSeconds(60)).block(Duration.ofSeconds(2));
            l1.invalidateAll();
            var supplement=cache.get(cacheKey).block(Duration.ofSeconds(2));
            assertTrue(supplement.isPresent(),"real Redis hit after clearing L1 is required");
            report.put("redisHitMs",TraceStore.get("web.cache.latencyMs"));
            assertEquals("redis",TraceStore.get("web.cache.tier"));
            cache.get(cacheKey).block();
            assertEquals("local",TraceStore.get("web.cache.tier"));
            report.put("localHitMs",TraceStore.get("web.cache.latencyMs"));
            String prompt=new StandardPromptBuilder().build(PromptContext.builder()
                    .userQuery("In two English sentences, explain what must match to prevent vector dimension errors and why equal dimensions do not prove model compatibility.")
                    .rag(retrieved).web(List.of(Content.from(TextSegment.from(supplement.orElseThrow()))))
                    .ragEnabled(true).build());
            began=System.nanoTime();
            var model=OpenAiChatModel.builder().apiKey(System.getenv("OPENAI_API_KEY"))
                    .modelName("gpt-4.1-mini").maxTokens(192).maxRetries(0).timeout(Duration.ofSeconds(30)).build();
            String answer=model.chat(prompt);
            String normalized=answer.toLowerCase(Locale.ROOT);
            assertTrue(normalized.contains("dimension") && normalized.contains("model")
                    && (normalized.contains("match")||normalized.contains("same")||normalized.contains("equal")),
                    "final response must preserve dimension and model compatibility constraints");
            report.put("finalResponseMs",(System.nanoTime()-began)/1_000_000);
            report.put("finalResponseLength",answer.length());
            report.put("finalResponseSemanticPass",true);
            report.put("finalModel","gpt-4.1-mini");
            report.put("finalModelScope","bounded synthetic integration proof; default local chat model unchanged");
            report.put("embeddingMs",TraceStore.get("rag.pipeline.embedding.latencyMs"));
            report.put("vectorMs",TraceStore.get("rag.pipeline.vector.latencyMs"));
            report.put("retrievalMs",TraceStore.get("rag.pipeline.retrieval.latencyMs"));
            report.put("pipelineStatus","passed");
        } catch(Exception failure) {
            report.put("status","failed");
            report.put("failureType",failure.getClass().getSimpleName());
            throw new AssertionError("live_pipeline_failed_"+failure.getClass().getSimpleName());
        } finally {
            // The existing fingerprint decorator deliberately exposes add/search only.
            // Cleanup uses the exact-ID adapter operation, never namespace deletion.
            try { new com.example.lms.service.vector.PineconeVectorStoreAdapter(http,props,fp,Duration.ofSeconds(3)).removeAll(ids);report.put("ownedVectorDeleteAccepted",true); }
            catch(Exception cleanup) { report.put("ownedVectorDeleteAccepted",false); }
            try {
                var check=EmbeddingSearchRequest.builder().queryEmbedding(vectors.get(0)).filter(new IsEqualTo("sid",sid)).maxResults(1).build();
                boolean empty=false;
                for(int attempt=0;attempt<5;attempt++) {
                    if(pinecone.search(check).matches().isEmpty()) {empty=true;break;}
                    Thread.sleep(1000);
                }
                report.put("ownedVectorDeletionVerified",empty);
            } catch(Exception cleanup) { report.put("ownedVectorDeletionVerified",false); }
            try {
                var response=http.post().uri(System.getenv("UPSTASH_REDIS_REST_URL").replaceAll("/$","")+"/pipeline")
                        .header("Authorization","Bearer "+System.getenv("UPSTASH_REDIS_REST_TOKEN"))
                        .bodyValue(List.of(List.of("DEL",cacheKey))).retrieve().bodyToMono(com.fasterxml.jackson.databind.JsonNode.class).block(Duration.ofSeconds(3));
                report.put("ownedRedisDeleteAccepted",response!=null&&response.get(0).path("result").asInt()==1);
            } catch(Exception cleanup) { report.put("ownedRedisDeleteAccepted",false); }
            report.put("status", "passed".equals(report.get("pipelineStatus"))
                    && Boolean.TRUE.equals(report.get("ownedVectorDeletionVerified"))
                    && Boolean.TRUE.equals(report.get("ownedRedisDeleteAccepted")) ? "passed" : "failed");
            ReflectionTestUtils.invokeMethod(federation,"shutdownPool");
            ReflectionTestUtils.invokeMethod(rag,"shutdownVectorBudgetExecutor");
            Files.writeString(evidence.resolve("live-pipeline.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            TraceStore.clear();
        }
        assertEquals(true,report.get("ownedVectorDeletionVerified"),"owned synthetic vectors must be removed");
        assertEquals(true,report.get("ownedRedisDeleteAccepted"),"owned Redis key must be removed");
    }
}
