package com.example.lms.infra.upstash;

import com.abandonware.ai.addons.budget.*;
import com.example.lms.search.TraceStore;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebCacheOutageFallbackTest {
    @AfterEach void clear() { TimeBudgetContext.clear(); TraceStore.clear(); }

    @Test void stalledRemoteReadBecomesCacheMissInsideOriginalBudget() {
        var remote=mock(UpstashRedisClient.class);
        var calls=new AtomicInteger();
        when(remote.get("synthetic-key")).thenReturn(Mono.defer(()->{calls.incrementAndGet();return Mono.never();}));
        var cache=new UpstashBackedWebCache(Caffeine.newBuilder().<String,String>build(),remote);
        TimeBudgetContext.set(new TimeBudget(80));
        assertEquals(Optional.empty(),cache.get("synthetic-key").block(Duration.ofMillis(500)));
        assertEquals(1,calls.get());
        assertEquals("cache_miss",TraceStore.get("web.cache.selectedRoute"));
    }

    @Test void stalledRemoteWriteKeepsTheLocalValueAndCompletesWithoutRetry() {
        var remote=mock(UpstashRedisClient.class);
        when(remote.setEx(anyString(),anyString(),any())).thenReturn(Mono.never());
        var local=Caffeine.newBuilder().<String,String>build();
        var cache=new UpstashBackedWebCache(local,remote);
        TimeBudgetContext.set(new TimeBudget(80));
        cache.put("synthetic-key","synthetic-result",Duration.ofSeconds(1)).block(Duration.ofMillis(500));
        assertEquals(Optional.of("synthetic-result"),cache.get("synthetic-key").block());
        verify(remote,times(1)).setEx(anyString(),anyString(),any());
        verify(remote,never()).get(anyString());
    }

    @Test void exhaustedBudgetSkipsRemoteCacheAndEmptyRemoteStillEmitsAMiss() {
        var remote=mock(UpstashRedisClient.class);
        when(remote.get(anyString())).thenReturn(Mono.empty());
        var cache=new UpstashBackedWebCache(Caffeine.newBuilder().<String,String>build(),remote);
        assertEquals(Optional.empty(),cache.get("empty").block());
        TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()-1));
        assertEquals(Optional.empty(),cache.get("expired").block());
        verify(remote,never()).get("expired");
    }

    @Test void braveResultSurvivesStalledRedisReadAndWriteWithoutRepeatingSearch() {
        var remote=mock(UpstashRedisClient.class);
        when(remote.get(anyString())).thenReturn(Mono.never());
        when(remote.setEx(anyString(),anyString(),any())).thenReturn(Mono.never());
        var cache=new UpstashBackedWebCache(Caffeine.newBuilder().<String,String>build(),remote);
        org.springframework.test.util.ReflectionTestUtils.setField(cache,"timeoutMs",40L);
        var limiter=mock(UpstashRateLimiter.class);
        when(limiter.allow(anyString(),anyLong(),any())).thenReturn(Mono.just(true));
        var calls=new AtomicInteger();
        var http=org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request->{
            calls.incrementAndGet();
            return Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                .header("Content-Type","application/json")
                .body("{\"web\":{\"results\":[{\"title\":\"fixture evidence\",\"url\":\"https://example.org/source-7\",\"description\":\"synthetic retained evidence\"}]}}").build());
        });
        var provider=new com.acme.aicore.adapters.search.BraveSearchProvider(http,cache,limiter);
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"apiKey","fixture-brave-key");
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"enabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"baseUrl","https://api.search.brave.com");
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"count",3);
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"timeoutMs",2000);
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"timeoutSec",2);
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"qps",1);
        var result=provider.search(new com.acme.aicore.domain.model.WebSearchQuery("synthetic cache outage")).block(Duration.ofSeconds(1));
        assertNotNull(result); assertEquals(1,result.docs().size()); assertEquals(1,calls.get());
        assertTrue(result.docs().toString().contains("source-7"));
        verify(remote,times(1)).get(anyString());
        verify(remote,times(1)).setEx(anyString(),anyString(),any());
    }

    @Test void unavailableRedisCannotGrantAdmission() throws Exception {
        var port=new java.net.ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress());
        int closedPort=port.getLocalPort(); port.close();
        var client=new UpstashRedisClient(org.springframework.web.reactive.function.client.WebClient.builder());
        org.springframework.test.util.ReflectionTestUtils.setField(client,"url","http://127.0.0.1:"+closedPort);
        org.springframework.test.util.ReflectionTestUtils.setField(client,"token","fixture-admission-key");
        var failure=assertThrows(RuntimeException.class,()->client.eval("return {1,0}",java.util.List.of(),java.util.List.of()).block(Duration.ofSeconds(3)));
        assertTrue(failure.toString().contains("redis_admission_unavailable"));
        assertFalse(failure.toString().contains("fixture-admission-key"));
    }

    @Test void cancellationIsNotConvertedIntoPermissionToContinueSearching() {
        var remote=mock(UpstashRedisClient.class);
        when(remote.get(anyString())).thenReturn(Mono.error(new CancellationException("synthetic-cancel")));
        var cache=new UpstashBackedWebCache(Caffeine.newBuilder().<String,String>build(),remote);
        assertThrows(CancellationException.class,()->cache.get("cancelled").block());
    }
}
