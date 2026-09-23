package com.example.lms.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiFailureRecorderTest {
    @TempDir Path dir;
    private final List<ApiFailureRecorder> ownedRecorders=new ArrayList<>();
    private ApiFailureRecorder recorder(Path path,Clock clock) {
        var recorder=new ApiFailureRecorder(null,path,clock);ownedRecorders.add(recorder);return recorder;
    }
    @org.junit.jupiter.api.AfterEach void closeWritersBeforeTempDirectoryCleanup() {
        ownedRecorders.forEach(ApiFailureRecorder::close);
    }

    @Test void distinguishesQuotaFromRateLimitWithoutGuessingUnknown429() {
        assertEquals("billing_quota", ApiFailureRecorder.classify(429,"{\"error\":{\"code\":\"insufficient_quota\"}}",null).category());
        assertEquals("rate_limit", ApiFailureRecorder.classify(429,"{\"error\":{\"code\":\"rate_limit_exceeded\"}}",null).category());
        assertEquals("quota_or_rate_limit", ApiFailureRecorder.classify(429,"unrecognized",null).category());
        assertEquals("permission_or_policy", ApiFailureRecorder.classify(403,"",null).category());
        assertEquals("authentication", ApiFailureRecorder.classify(401,"",null).category());
        assertEquals("model", ApiFailureRecorder.classify(404,"{\"error\":{\"code\":\"model_not_found\"}}",null).category());
        assertEquals("model_or_endpoint", ApiFailureRecorder.classify(404,"",null).category());
    }

    @Test void hostileResponseAndExceptionNeverEnterStoredIncident() throws Exception {
        var recorder=recorder(dir.resolve("failures.json"),Clock.systemUTC());
        recorder.record("openai","gpt-test",429,"{\"error\":{\"code\":\"private-conversation\",\"message\":\"sk-sensitive-secret\"}}",new RuntimeException("private-conversation"));
        assertTrue(recorder.awaitPersistence());
        var output=Files.readString(dir.resolve("failures.json"));
        assertFalse(output.contains("private-conversation"));
        assertFalse(output.contains("sk-sensitive-secret"));
        assertFalse(output.contains("RuntimeException"));
        assertEquals("quota_or_rate_limit",recorder.snapshot().get(0).category());
    }

    @Test void repeatsAreGroupedAndSurviveRestartWithFirstAndLastTimes() throws Exception {
        var first=Instant.parse("2026-09-16T00:00:00Z");
        var path=dir.resolve("failures.json");
        var initial=recorder(path,Clock.fixed(first,ZoneOffset.UTC));
        initial.record("groq","openai/gpt-oss-120b",403,"",null);initial.close();
        var recorder=recorder(path,Clock.fixed(first.plusSeconds(30),ZoneOffset.UTC));
        recorder.record("groq","openai/gpt-oss-120b",403,"",null);
        assertEquals(1,recorder.snapshot().size());
        var row=recorder.snapshot().get(0);
        assertEquals(2,row.count());
        assertEquals(first.toString(),row.firstSeen());
        assertEquals(first.plusSeconds(30).toString(),row.lastSeen());
    }

    @Test void fallbackSuccessCannotRemoveInitialFailureAndModelsStaySeparate() {
        var recorder=recorder(dir.resolve("failures.json"),Clock.systemUTC());
        recorder.record("openai","first-model",401,"",null);
        recorder.record("gemini","fallback-model",200,"",null);
        recorder.record("openai","second-model",401,"",null);
        assertEquals(2,recorder.snapshot().size());
        assertTrue(recorder.snapshot().stream().allMatch(r->r.provider().equals("openai")));
    }

    @Test void diskFailureCannotChangeProviderOutcome() throws Exception {
        var blocked=dir.resolve("file");Files.writeString(blocked,"synthetic");
        var recorder=recorder(blocked.resolve("failures.json"),Clock.systemUTC());
        assertDoesNotThrow(()->recorder.record("brave","not_applicable",500,"",null));
        assertTrue(recorder.awaitPersistence());
        assertEquals(1,recorder.snapshot().size());
        assertFalse(recorder.persistenceHealthy());
    }

    @Test void statusOnlyConsumerSeesFailureBeforeFallbackAndBodyCanRefineWithoutDoubleCount() {
        var recorder=recorder(dir.resolve("web.json"),Clock.systemUTC());
        String body="{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"private body\"}}";
        var request=org.springframework.web.reactive.function.client.ClientRequest.create(org.springframework.http.HttpMethod.POST,java.net.URI.create("https://api.openai.com/v1/chat/completions")).attribute("api.failure.model","gpt-test").build();
        var response=ApiFailureWebClientConfiguration.filter(recorder).filter(request,ignored->reactor.core.publisher.Mono.just(
                org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).body(body).build())).block();
        assertEquals(1,recorder.snapshot().size(),"failure must precede body consumption and fallback");
        assertEquals("quota_or_rate_limit",recorder.snapshot().get(0).category());
        assertEquals(body,response.bodyToMono(String.class).block(),"observation must not consume or alter downstream body");
        assertEquals(1,recorder.snapshot().get(0).count());
        assertEquals("billing_quota",recorder.snapshot().get(0).category());
        recorder.close();
    }

    @Test void actualLangchainHttpFailureIsRecordedBeforeFallbackWithoutAnotherProviderCall() throws Exception {
        var recorder=recorder(dir.resolve("wire.json"),Clock.systemUTC());
        var tracker=new com.example.lms.llm.ModelRuntimeHealthTracker();
        org.springframework.test.util.ReflectionTestUtils.setField(tracker,"apiFailureRecorder",recorder);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();var bytes="{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"synthetic-private\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(401,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
        try {
            var client=tracker.observedHttpClientBuilder("primary").build();
            var request=dev.langchain4j.http.client.HttpRequest.builder().url("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions").method(dev.langchain4j.http.client.HttpMethod.POST).body("{\"model\":\"gpt-test\",\"messages\":[]}").build();
            assertThrows(RuntimeException.class,()->client.execute(request));
            assertEquals(1,calls.get());assertEquals(1,recorder.snapshot().size());
            assertEquals("gpt-test",recorder.snapshot().get(0).model());
            assertEquals(401,recorder.snapshot().get(0).httpStatus());
            assertEquals("authentication",recorder.snapshot().get(0).category());
        }finally{server.stop(0);recorder.close();}
    }

    @Test void cyclicCausesTerminateAndCancellationIsNotAnApiFailure() {
        var recorder=recorder(dir.resolve("cycles.json"),Clock.systemUTC());
        var a=new RuntimeException("a");var b=new RuntimeException("b");a.initCause(b);b.initCause(a);
        assertTimeoutPreemptively(Duration.ofSeconds(1),()->recorder.recordException("https://api.groq.com", "test-model",a));
        recorder.recordException("https://api.groq.com","test-model",new RuntimeException(new java.util.concurrent.CancellationException()));
        assertEquals(1,recorder.snapshot().get(0).count());recorder.close();
    }

    @Test void failureClassEvidenceSeparatesProbeFactsFromInference() {
        var recorder=recorder(dir.resolve("classes.json"),Clock.systemUTC());
        recorder.recordFailureClass("local","qwen3.5:9b","GPU_DEVICE_LOST");
        recorder.recordFailureClass("openai","gpt-test","TIMEOUT_SOFT");
        recorder.recordFailureClass("openai","gpt-test","API_QUOTA_EXHAUSTED");
        recorder.recordFailureClass("openai","gpt-test","CANCELLED_NEUTRAL");
        var rows=recorder.snapshot();
        assertEquals(3,rows.size());
        var gpu=rows.stream().filter(r->r.category().equals("gpu_unavailable")).findFirst().orElseThrow();
        assertEquals("local",gpu.scope());assertEquals("CONFIRMED",gpu.evidence());
        var timeout=rows.stream().filter(r->r.category().equals("timeout")).findFirst().orElseThrow();
        assertEquals("llm",timeout.scope());assertEquals("SUSPECTED",timeout.evidence());
        var quota=rows.stream().filter(r->r.category().equals("quota_exhausted")).findFirst().orElseThrow();
        assertEquals("SUSPECTED",quota.evidence());
        recorder.close();
    }

    @Test void fallbackMasksButNeverErasesAndRecoveryResetsStreak() throws Exception {
        var first=Instant.parse("2026-09-18T00:00:00Z");
        var path=dir.resolve("mask.json");
        var recorder=recorder(path,Clock.fixed(first,ZoneOffset.UTC));
        recorder.record("openai","gpt-a",0,null,new java.net.SocketTimeoutException());
        recorder.record("openai","gpt-a",0,null,new java.net.SocketTimeoutException());
        var row=recorder.snapshot().get(0);
        assertEquals(2,row.consecutive());assertEquals(2,row.count());
        recorder.markMasked("openai","gpt-a","gemini","gemini-b");
        var masked=recorder.snapshot().get(0);
        assertEquals("gemini/gemini-b",masked.maskedBy());assertNotNull(masked.maskedAt());
        assertEquals(2,masked.count(),"fallback success must not erase the original failure");
        assertTrue(recorder.awaitPersistence());
        var recovered=recorder(path,Clock.fixed(first.plusSeconds(120),ZoneOffset.UTC));
        assertEquals(2,recovered.snapshot().get(0).consecutive(),"streak survives restart");
        recovered.recordSuccess("openai","gpt-a");
        var after=recovered.snapshot().get(0);
        assertEquals(0,after.consecutive());
        assertEquals(first.plusSeconds(120).toString(),after.lastSuccessAt());
        assertEquals(first.plusSeconds(120).toString(),after.recoveredAt());
        assertEquals(2,after.count());assertEquals("gemini/gemini-b",after.maskedBy());
        recovered.close();
    }

    @Test void zeroSearchResultIsNotAnIncidentButProviderFailureIs() {
        var recorder=recorder(dir.resolve("search.json"),Clock.systemUTC());
        assertNull(recorder.recordSearch("naver","not_applicable","TRUE_ZERO"));
        assertNull(recorder.recordSearch("naver","not_applicable","NONE"));
        assertNull(recorder.recordSearch("naver","not_applicable","insufficient_results"));
        assertTrue(recorder.snapshot().isEmpty());
        recorder.recordSearch("naver","not_applicable","AUTH_OR_CONFIG");
        recorder.recordSearch("brave","not_applicable","RATE_LIMIT");
        assertEquals(2,recorder.snapshot().size());
        var naver=recorder.snapshot().stream().filter(r->r.provider().equals("naver")).findFirst().orElseThrow();
        assertEquals("authentication",naver.category());assertEquals("search",naver.scope());
        assertEquals("CONFIRMED",naver.evidence());
        recorder.close();
    }

    @Test void statusSummaryFlagsDegradedWarningAndRecovered() {
        var now=Instant.parse("2026-09-18T12:00:00Z");
        var recorder=recorder(dir.resolve("status.json"),Clock.fixed(now,ZoneOffset.UTC));
        recorder.record("openai","gpt-a",0,null,new java.net.SocketTimeoutException());
        recorder.record("openai","gpt-a",0,null,new java.net.SocketTimeoutException());
        recorder.record("soniox","stt-rt-v5",0,null,null);
        recorder.record("gemini","g-b",500,"",null);
        recorder.recordSuccess("gemini","g-b");
        var status=recorder.statusSummary();
        assertEquals("DEGRADED",status.get("overall"));
        var providers=(List<Map<String,Object>>)status.get("providers");
        var openai=providers.stream().filter(p->p.get("provider").equals("openai")).findFirst().orElseThrow();
        assertEquals("DEGRADED",openai.get("state"));assertEquals(2L,openai.get("consecutive"));
        var soniox=providers.stream().filter(p->p.get("provider").equals("soniox")).findFirst().orElseThrow();
        assertEquals("WARNING",soniox.get("state"));assertEquals("stt",soniox.get("scope"));
        var gemini=providers.stream().filter(p->p.get("provider").equals("gemini")).findFirst().orElseThrow();
        assertEquals("OK",gemini.get("state"));assertEquals(now.toString(),gemini.get("recoveredAt"));
        assertEquals("gemini",providers.get(providers.size()-1).get("provider"),"healthy providers sort last");
        recorder.close();
    }

    @Test void legacyRowsWithoutNewFieldsLoadWithSafeDefaults() throws Exception {
        var path=dir.resolve("legacy.json");
        Files.writeString(path,"[{\"provider\":\"openai\",\"model\":\"gpt-old\",\"httpStatus\":429,"
                +"\"category\":\"quota_or_rate_limit\",\"errorCode\":\"unconfirmed\","
                +"\"firstSeen\":\"2026-09-18T00:00:00Z\",\"lastSeen\":\"2026-09-18T00:01:00Z\",\"count\":7}]");
        var recorder=recorder(path,Clock.systemUTC());
        assertTrue(recorder.persistenceHealthy());
        var row=recorder.snapshot().get(0);
        assertEquals(7,row.count());assertEquals("llm",row.scope());assertEquals("UNKNOWN",row.evidence());
        assertNull(row.maskedBy());
        recorder.close();
    }

    @Test void normalizedAsrSignalDoesNotInventHttpStatusOrProviderErrorCode() {
        var recorder=recorder(dir.resolve("asr.json"),Clock.systemUTC());
        recorder.recordSignal("soniox","stt-rt-v5","ASR_AUTH_FAILED");
        var row=recorder.snapshot().get(0);
        assertNull(row.httpStatus());assertEquals("unconfirmed",row.errorCode());
        assertEquals("authentication_or_permission",row.category());recorder.close();
    }

    @Test void springInstallsRecorderAndHttpObserverWithoutEnablingProviders() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(ApiFailureRecorder.class,ApiFailureWebClientConfiguration.class,com.example.lms.llm.ModelRuntimeHealthTracker.class)
                .withBean(DebugEventStore.class,DebugEventStore::new)
                .withPropertyValues("lms.api-failures.path="+dir.resolve("spring.json"),"abandonware.debug.ndjson.enabled=false")
                .run(context->{
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(ApiFailureRecorder.class));
                    assertNotNull(context.getBean(org.springframework.boot.web.reactive.function.client.WebClientCustomizer.class));
                    var tracker=context.getBean(com.example.lms.llm.ModelRuntimeHealthTracker.class);
                    tracker.recordApiFailure("https://api.groq.com/openai/v1/chat/completions","fixture-model",new dev.langchain4j.exception.HttpException(403,"private-fixture"));
                    assertEquals(1,context.getBean(ApiFailureRecorder.class).snapshot().size());
                });
    }
}
