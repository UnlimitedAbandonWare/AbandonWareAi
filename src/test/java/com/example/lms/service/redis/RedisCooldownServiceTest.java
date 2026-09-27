package com.example.lms.service.redis;

import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisCooldownServiceTest {

    @Test
    void sharedJedisClientIsNeverEnteredConcurrently() throws Exception {
        RedisCooldownService service = new RedisCooldownService("127.0.0.1", 1);
        Jedis jedis = mock(Jedis.class);
        ReflectionTestUtils.setField(service, "jedis", jedis);

        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstEntry = new CountDownLatch(1);
        CountDownLatch concurrentEntry = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenAnswer(invocation -> {
            int active = inFlight.incrementAndGet();
            firstEntry.countDown();
            if (active > 1) {
                concurrentEntry.countDown();
            }
            try {
                assertTrue(releaseCommand.await(5, TimeUnit.SECONDS));
                return "OK";
            } finally {
                inFlight.decrementAndGet();
            }
        });

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> invokeAfterStart(
                    service, callersReady, start, "cooldown:first"));
            Future<Boolean> second = workers.submit(() -> invokeAfterStart(
                    service, callersReady, start, "cooldown:second"));

            assertTrue(callersReady.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(firstEntry.await(2, TimeUnit.SECONDS));
            boolean overlapped = concurrentEntry.await(1, TimeUnit.SECONDS);
            releaseCommand.countDown();

            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertTrue(second.get(2, TimeUnit.SECONDS));
            assertFalse(overlapped, "one shared Jedis connection must not receive overlapping commands");
        } finally {
            start.countDown();
            releaseCommand.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void closeIsOwnedBySpringBeanLifecycle() throws Exception {
        assertNotNull(RedisCooldownService.class
                .getDeclaredMethod("close")
                .getAnnotation(PreDestroy.class));
    }

    private static boolean invokeAfterStart(
            RedisCooldownService service,
            CountDownLatch callersReady,
            CountDownLatch start,
            String key) throws InterruptedException {
        callersReady.countDown();
        assertTrue(start.await(2, TimeUnit.SECONDS));
        return service.setNxEx(key, "1", 1);
    }
}
