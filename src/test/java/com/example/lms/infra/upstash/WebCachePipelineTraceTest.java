package com.example.lms.infra.upstash;

import com.example.lms.search.TraceStore;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebCachePipelineTraceTest {
    @AfterEach void clear() { TraceStore.clear(); }
    @Test void redisHitPopulatesLocalAndAsyncTelemetryStaysOnOriginalRequest() {
        var remote=mock(UpstashRedisClient.class);
        when(remote.get("synthetic")).thenReturn(Mono.just("result").subscribeOn(Schedulers.boundedElastic()));
        var cache=new UpstashBackedWebCache(Caffeine.newBuilder().<String,String>build(),remote);
        assertThat(cache.get("synthetic").block().orElseThrow()).isEqualTo("result");
        assertThat(TraceStore.get("web.cache.tier")).isEqualTo("redis");
        assertThat(TraceStore.get("web.cache.result")).isEqualTo("hit");
        cache.get("synthetic").block();
        assertThat(TraceStore.get("web.cache.tier")).isEqualTo("local");
        verify(remote,times(1)).get("synthetic");
    }
    @Test void disabledRemoteDoesNotChangeLocalCacheOrMakeNetworkCalls() {
        var remote=mock(UpstashRedisClient.class);
        var cache=new UpstashBackedWebCache(Caffeine.newBuilder().<String,String>build(),remote);
        ReflectionTestUtils.setField(cache,"remoteEnabled",false);
        assertThat(cache.get("synthetic").block()).isEmpty();
        assertThat(TraceStore.get("web.cache.result")).isEqualTo("miss");
        cache.put("synthetic","result",Duration.ofSeconds(5)).block();
        assertThat(cache.get("synthetic").block()).contains("result");
        verifyNoInteractions(remote);
    }
}
