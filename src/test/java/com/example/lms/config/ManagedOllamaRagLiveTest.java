package com.example.lms.config;

import com.example.lms.service.embedding.*;
import com.example.lms.vector.EmbeddingFingerprint;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit once-only live test against the task-owned managed server, never an operating index. */
class ManagedOllamaRagLiveTest {
    @Test void realEmbeddingMemoryRetrievalCanonicalAnswerMixedLoadAndTruncation() throws Exception {
        assumeTrue("true".equals(System.getenv("AWX_GPU_RAG_CANARY")), "task-owned real Ollama canary disabled");
        Path dir = Path.of(System.getenv("AWX_GPU_RAG_REPORT_DIR")).toAbsolutePath().normalize();
        assertThat(dir.startsWith(Path.of("verification/gpu-runtime-postprocess-20260908").toAbsolutePath().normalize())).isTrue();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var runtime = mapper.readTree(Files.readString(dir.resolve("runtime.json")));
        String target = runtime.path("managed").path("endpoint").asText();
        URI uri = URI.create(target);
        assertThat(uri.getScheme()).isEqualTo("http"); assertThat(uri.getHost()).isEqualTo("127.0.0.1");
        assertThat(runtime.path("role").asText()).isEqualTo("fast");
        long pid = runtime.path("managed").path("pid").asLong();
        var process = ProcessHandle.of(pid).orElseThrow(); assertThat(process.isAlive()).isTrue();
        assertThat(Math.abs(process.info().startInstant().orElseThrow().toEpochMilli()
                - runtime.path("managed").path("startedAtEpochMs").asLong())).isLessThan(1000);
        var report = new LinkedHashMap<String,Object>();
        report.put("runId", runtime.path("runId").asText()); report.put("jarSha256", runtime.path("jarSha256").asText());
        report.put("scope", "active_class_real_ollama_isolated_memory_store"); report.put("providerRequestCeiling", 7);
        try {
            var delegate = new OllamaEmbeddingModel(WebClient.create());
            ReflectionTestUtils.setField(delegate, "provider", "ollama");
            ReflectionTestUtils.setField(delegate, "model", "qwen3-embedding:4b");
            ReflectionTestUtils.setField(delegate, "apiUrl", target+"/api/embed");
            ReflectionTestUtils.setField(delegate, "dimensions", 1536);
            ReflectionTestUtils.setField(delegate, "timeoutSec", 30);
            ReflectionTestUtils.setField(delegate, "normalizationMode", "SLICE_TO_CONFIGURED_DIM");
            var fp = new EmbeddingFingerprint();
            ReflectionTestUtils.setField(fp, "provider", "ollama"); ReflectionTestUtils.setField(fp, "model", "qwen3-embedding:4b");
            ReflectionTestUtils.setField(fp, "dimensions", 1536);
            var embeddings = new DecoratingEmbeddingModel(delegate, new EmbeddingCache.InMemory(), Duration.ofMinutes(2), fp);
            var beans = new DefaultListableBeanFactory(); beans.registerSingleton("store", new InMemoryEmbeddingStore<TextSegment>());
            @SuppressWarnings("unchecked") ObjectProvider<EmbeddingStore<TextSegment>> provider = (ObjectProvider)beans.getBeanProvider(EmbeddingStore.class);
            var store = new LangChainConfig(null,null).embeddingStore(null,provider,fp,null,"memory");
            var docs=List.of(TextSegment.from("The Cerulean laboratory archive vault unlock code is ORCHID-472."),
                    TextSegment.from("The Northbridge observatory dome is opened with a silver mechanical lever."),
                    TextSegment.from("The Redstone museum ticket office closes at six in the evening."));
            com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(30000));
            long started=System.nanoTime();
            var vectors=embeddings.embedAll(docs).content();
            assertThat(vectors).hasSize(3); assertThat(vectors).allMatch(v->v.vector().length==1536);
            store.addAll(vectors,docs);
            String query="What is the Cerulean laboratory archive vault unlock code? Answer only the code.";
            var vector=embeddings.embed(query).content();
            var matches=store.search(EmbeddingSearchRequest.builder().queryEmbedding(vector).maxResults(3).minScore(0.0d).build()).matches();
            assertThat(matches).isNotEmpty(); assertThat(matches.get(0).embedded().text()).contains("ORCHID-472");
            var ctx=com.example.lms.prompt.PromptContext.builder().userQuery(query).ragEnabled(true)
                    .rag(matches.stream().map(m->dev.langchain4j.rag.content.Content.from(m.embedded())).toList()).build();
            String prompt=new com.example.lms.prompt.StandardPromptBuilder().build(ctx);
            assertThat(prompt).contains("ORCHID-472");
            var chat=new com.example.lms.llm.OllamaNativeChatModel(target+"/v1","qwen3:8b",Duration.ofSeconds(30),512,0.0);
            String answer=chat.chat(List.of(UserMessage.from(prompt))).aiMessage().text();
            assertThat(answer).contains("ORCHID-472");
            report.put("rag",Map.of("documentCount",3,"dimension",1536,"retrievedCount",matches.size(),"correctTopDocument",true,
                    "canonicalPromptContainsEvidence",true,"answerSemanticMatch",true,"answerHash",com.example.lms.trace.SafeRedactor.hashValue(answer),
                    "elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"fingerprint",fp.fingerprint()));
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
            var pool=Executors.newFixedThreadPool(2); var release=new CountDownLatch(1);
            try {
                long mixedStarted=System.nanoTime();
                var generation=pool.submit(()->{release.await(); return chat.chat(List.of(UserMessage.from("Briefly explain binary search in three sentences."))).aiMessage().text();});
                var embedding=pool.submit(()->{release.await(); return embeddings.embed("The mixed workload has a separate embedding input.").content().vector().length;});
                release.countDown();
                assertThat(generation.get(40,TimeUnit.SECONDS)).isNotBlank(); assertThat(embedding.get(40,TimeUnit.SECONDS)).isEqualTo(1536);
                report.put("mixed",Map.of("concurrency",2,"generationSucceeded",true,"embeddingSucceeded",true,
                        "elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-mixedStarted),"cancelled",0,"failed",0));
            } finally {pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
            // Explicit false and installed default are compared; no latency improvement claim uses these samples.
            var http=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            String input="word ".repeat(40000);
            var strictBody=new LinkedHashMap<String,Object>(); strictBody.put("model","qwen3-embedding:4b"); strictBody.put("input",input); strictBody.put("truncate",false);
            var strict=http.send(java.net.http.HttpRequest.newBuilder(URI.create(target+"/api/embed")).timeout(Duration.ofSeconds(90))
                    .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(strictBody))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(strict.statusCode()).isEqualTo(400);
            boolean contextRejected=strict.body().toLowerCase(Locale.ROOT).contains("input length") || strict.body().toLowerCase(Locale.ROOT).contains("context");
            assertThat(contextRejected).isTrue();
            strictBody.remove("truncate");
            var defaults=http.send(java.net.http.HttpRequest.newBuilder(URI.create(target+"/api/embed")).timeout(Duration.ofSeconds(90))
                    .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(strictBody))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(defaults.statusCode()).isEqualTo(200);
            var counts=mapper.readTree(defaults.body());
            report.put("longInput",Map.of("inputWordCount",40000,"inputChars",input.length(),"strictStatus",400,"strictContextRejected",true,
                    "defaultStatus",200,"promptEvalCount",counts.path("prompt_eval_count").asLong(-1),
                    "totalDurationNs",counts.path("total_duration").asLong(-1),"defaultTruncationInferred",true,"excludedFromPerformance",true));
            report.put("passed",true);
        } finally {
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
            Files.writeString(dir.resolve("live-rag.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            Files.writeString(dir.resolve("live-tests.done"),"complete");
        }
    }
}
