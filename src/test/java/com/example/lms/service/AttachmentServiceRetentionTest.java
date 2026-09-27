package com.example.lms.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.LmsApplication;
import com.example.lms.dto.AttachmentDto;
import com.example.lms.file.FileIngestionService;
import com.example.lms.search.TraceStore;
import com.example.lms.storage.LocalFileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentServiceRetentionTest {
    private LocalFileStorageService storage;
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        storage = mock(LocalFileStorageService.class);
        service = serviceWithTtl(storage, "10");
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void evictsExpiredMetadataAcrossEveryMapWithoutPhysicalStorageIo() {
        String expiredId = "expired-fixture-id";
        String freshId = "fresh-fixture-id";
        String expiredSession = "expired-fixture-session";
        String freshSession = "fresh-fixture-session";
        seed(service, expiredId, 90L);
        seed(service, freshId, 91L);
        map(service, "extractedTextById", String.class).put(expiredId, "expired text");
        map(service, "extractedTextById", String.class).put(freshId, "fresh text");
        map(service, "contentDigestById", String.class).put(expiredId, "expired digest");
        map(service, "contentDigestById", String.class).put(freshId, "fresh digest");
        service.attachToSession(expiredSession, List.of(expiredId));
        service.attachToSession(freshSession, List.of(freshId));

        assertEquals(1, service.evictExpired(100L));

        assertFalse(map(service, "repo", AttachmentDto.class).containsKey(expiredId));
        assertFalse(map(service, "extractedTextById", String.class).containsKey(expiredId));
        assertFalse(map(service, "contentDigestById", String.class).containsKey(expiredId));
        assertFalse(map(service, "retainedAtEpochMsById", Long.class).containsKey(expiredId));
        assertFalse(sessionIndex(service).containsKey(expiredSession));

        assertTrue(map(service, "repo", AttachmentDto.class).containsKey(freshId));
        assertTrue(map(service, "extractedTextById", String.class).containsKey(freshId));
        assertTrue(map(service, "contentDigestById", String.class).containsKey(freshId));
        assertEquals(91L, map(service, "retainedAtEpochMsById", Long.class).get(freshId));
        assertEquals(List.of(freshId), service.findIdsBySession(freshSession, 10));
        assertEquals(List.of(freshId),
                service.findBySession(freshSession).stream().map(AttachmentDto::id).toList());

        verify(storage).delete("/uploads/" + expiredId);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(expiredId));
        assertFalse(trace.contains(expiredSession));
        assertFalse(trace.contains(freshSession));
    }

    @Test
    void expiryBoundaryClockRollbackAndExtremeTtlAreOverflowSafe() {
        seed(service, "at-boundary", 90L);
        seed(service, "before-boundary", 91L);
        seed(service, "future-clock", 101L);

        assertEquals(1, service.evictExpired(100L));
        assertFalse(service.find("at-boundary").isPresent(), "age == TTL must expire");
        assertTrue(service.find("before-boundary").isPresent(), "age == TTL - 1 must remain live");
        assertTrue(service.find("future-clock").isPresent(), "future timestamp must survive clock rollback");

        AttachmentService extreme = serviceWithTtl(mock(LocalFileStorageService.class),
                String.valueOf(Long.MAX_VALUE));
        seed(extreme, "extreme-live", 1L);
        seed(extreme, "overflow-expired", Long.MIN_VALUE);

        assertEquals(1, extreme.evictExpired(Long.MAX_VALUE));
        assertTrue(extreme.find("extreme-live").isPresent(),
                "extreme positive TTL must not overflow into premature expiry");
        assertFalse(extreme.find("overflow-expired").isPresent(),
                "a mathematically older-than-TTL timestamp must expire even when subtraction overflows");
    }

    @Test
    void invalidOrNonpositiveTtlFallsBackToPositiveDefault() {
        for (String raw : List.of("malformed", "0", "-1")) {
            AttachmentService candidate = serviceWithTtl(mock(LocalFileStorageService.class), raw);
            long now = AttachmentService.DEFAULT_RETENTION_TTL_MS + 100L;
            seed(candidate, "fallback-live-" + raw, 101L);
            seed(candidate, "fallback-expired-" + raw, 100L);

            assertEquals(1, candidate.evictExpired(now), "raw TTL=" + raw);
            assertTrue(candidate.find("fallback-live-" + raw).isPresent(), "raw TTL=" + raw);
            assertFalse(candidate.find("fallback-expired-" + raw).isPresent(), "raw TTL=" + raw);
        }
    }

    @Test
    void registrationAfterConcurrentCleanupPublishesDtoAndTimestampTogether() throws Exception {
        CountDownLatch storageEntered = new CountDownLatch(1);
        CountDownLatch releaseStorage = new CountDownLatch(1);
        MockMultipartFile file = new MockMultipartFile(
                "files", "atomic.txt", "text/plain", "atomic registration".getBytes());
        when(storage.save(any(MultipartFile.class), eq("chat"))).thenAnswer(invocation -> {
            storageEntered.countDown();
            await(releaseStorage);
            return "/uploads/chat/atomic.txt";
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<AttachmentDto>> registration =
                    executor.submit(() -> service.saveAll(List.of(file), "atomic-session"));
            assertTrue(storageEntered.await(5, TimeUnit.SECONDS));

            assertEquals(0, service.evictExpired(Long.MAX_VALUE),
                    "cleanup before successful registration must see no partial metadata");
            releaseStorage.countDown();

            List<AttachmentDto> saved = registration.get(5, TimeUnit.SECONDS);
            assertEquals(1, saved.size());
            String id = saved.get(0).id();
            assertTrue(map(service, "repo", AttachmentDto.class).containsKey(id));
            Long retainedAt = map(service, "retainedAtEpochMsById", Long.class).get(id);
            assertNotNull(retainedAt);
            assertTrue(retainedAt > 0L);
            assertEquals(List.of(id), service.findIdsBySession("atomic-session", 10));
            verify(storage).save(file, "chat");
        } finally {
            releaseStorage.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sessionRegistrationDoesNotExposeCleanupGapThroughOneArgumentOverload() throws Exception {
        LocalFileStorageService candidateStorage = mock(LocalFileStorageService.class);
        MockMultipartFile file = new MockMultipartFile(
                "files", "session-atomic.txt", "text/plain", "session atomic".getBytes());
        when(candidateStorage.save(file, "chat")).thenReturn("/uploads/chat/session-atomic.txt");
        PausingDelegationAttachmentService candidate =
                new PausingDelegationAttachmentService(candidateStorage);
        ReflectionTestUtils.setField(candidate, "environment",
                new MockEnvironment().withProperty("attachments.retention.ttl-ms", "10"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<AttachmentDto>> upload =
                    executor.submit(() -> candidate.saveAll(List.of(file), "session-atomic-owner"));
            awaitDelegationOrCompletion(candidate.oneArgumentReturned, upload);

            if (candidate.oneArgumentReturned.getCount() == 0L) {
                assertEquals(1, candidate.evictExpired(Long.MAX_VALUE),
                        "cleanup must reproduce the registration-to-session-link gap");
                candidate.releaseOneArgumentReturn.countDown();
            }

            List<AttachmentDto> saved = upload.get(5, TimeUnit.SECONDS);
            String id = saved.get(0).id();
            assertAll(
                    () -> assertFalse(candidate.oneArgumentCalled.get(),
                            "session-aware registration must not delegate through a separately locked public overload"),
                    () -> assertTrue(candidate.find(id).isPresent(),
                            "returned attachment metadata must remain live"),
                    () -> assertEquals(List.of(id),
                            candidate.findIdsBySession("session-atomic-owner", 10),
                            "returned attachment must be discoverable through its session")
            );
        } finally {
            candidate.releaseOneArgumentReturn.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void readsNeverRefreshRegistrationTimestamp() {
        String id = "fixed-registration-id";
        seed(service, id, 123L);
        service.attachToSession("fixed-registration-session", List.of(id));
        long before = map(service, "retainedAtEpochMsById", Long.class).get(id);

        assertTrue(service.find(id).isPresent());
        assertEquals(1, service.findBySession("fixed-registration-session").size());
        assertEquals(List.of(id), service.findIdsBySession("fixed-registration-session", 10));

        assertEquals(before, map(service, "retainedAtEpochMsById", Long.class).get(id));
    }

    @Test
    void expirySerializesAndRejectsConcurrentPostExpiryTextAndSessionWrites() throws Exception {
        String id = "expiry-writer-race-id";
        BlockingRemoveMap<String, AttachmentDto> blockingRepo = new BlockingRemoveMap<>(id);
        ReflectionTestUtils.setField(service, "repo", blockingRepo);
        seed(service, id, 90L);
        service.attachToSession("existing-race-session", List.of(id));

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Integer> eviction = executor.submit(() -> service.evictExpired(100L));
            assertTrue(blockingRepo.removeEntered.await(5, TimeUnit.SECONDS));

            CountDownLatch writersStarted = new CountDownLatch(2);
            Future<?> textWriter = executor.submit(() -> {
                writersStarted.countDown();
                service.cacheExtractedText(id, "must not survive");
            });
            Future<?> sessionWriter = executor.submit(() -> {
                writersStarted.countDown();
                service.attachToSession("late-race-session", List.of(id));
            });
            assertTrue(writersStarted.await(5, TimeUnit.SECONDS));
            assertFalse(textWriter.isDone(), "text writer must wait for the metadata mutation owner");
            assertFalse(sessionWriter.isDone(), "session writer must wait for the metadata mutation owner");

            blockingRepo.releaseRemove.countDown();
            assertEquals(1, eviction.get(5, TimeUnit.SECONDS));
            textWriter.get(5, TimeUnit.SECONDS);
            sessionWriter.get(5, TimeUnit.SECONDS);

            assertFalse(map(service, "repo", AttachmentDto.class).containsKey(id));
            assertFalse(map(service, "extractedTextById", String.class).containsKey(id));
            assertFalse(map(service, "retainedAtEpochMsById", Long.class).containsKey(id));
            assertFalse(sessionIndex(service).values().stream().anyMatch(ids -> ids.contains(id)));
        } finally {
            blockingRepo.releaseRemove.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void expiryCannotDiscardDifferentFreshLinkAddedToSameSession() throws Exception {
        String session = "shared-session-race";
        String expiredId = "old-session-member";
        String freshId = "fresh-session-member";
        BlockingConditionalRemoveMap<String, List<String>> sessions =
                new BlockingConditionalRemoveMap<>(session);
        ReflectionTestUtils.setField(service, "sessionIndex", sessions);
        seed(service, expiredId, 90L);
        seed(service, freshId, 99L);
        sessions.put(session, new CopyOnWriteArrayList<>(List.of(expiredId)));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> eviction = executor.submit(() -> service.evictExpired(100L));
            assertTrue(sessions.removeEntered.await(5, TimeUnit.SECONDS));

            CountDownLatch writerStarted = new CountDownLatch(1);
            Future<?> freshLink = executor.submit(() -> {
                writerStarted.countDown();
                service.attachToSession(session, List.of(freshId));
            });
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            assertFalse(freshLink.isDone(), "fresh link must wait until remove-empty is complete");

            sessions.releaseRemove.countDown();
            assertEquals(1, eviction.get(5, TimeUnit.SECONDS));
            freshLink.get(5, TimeUnit.SECONDS);

            assertEquals(List.of(freshId), service.findIdsBySession(session, 10));
            assertEquals(List.of(freshId),
                    service.findBySession(session).stream().map(AttachmentDto::id).toList());
        } finally {
            sessions.releaseRemove.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void scheduledCleanupIsReachableAndLogsOnlyAggregateCount() throws Exception {
        Method cleanup = AttachmentService.class.getDeclaredMethod("evictExpiredAttachments");
        Scheduled scheduled = cleanup.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals("${attachments.retention.cleanup-interval-ms:300000}",
                scheduled.fixedDelayString());
        assertEquals(86_400_000L, AttachmentService.DEFAULT_RETENTION_TTL_MS);
        assertTrue(LmsApplication.class.isAnnotationPresent(EnableScheduling.class));
        assertTrue(AttachmentService.class.isAnnotationPresent(Service.class));

        String rawId = "scheduled-private-id";
        String rawSession = "scheduled-private-session";
        seed(service, rawId, 0L);
        service.attachToSession(rawSession, List.of(rawId));

        Logger logger = (Logger) LoggerFactory.getLogger(AttachmentService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            service.evictExpiredAttachments();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }

        String messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList()
                .toString();
        assertTrue(messages.contains("expired metadata evicted count=1"));
        assertFalse(messages.contains(rawId));
        assertFalse(messages.contains(rawSession));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawId));
        verify(storage).delete("/uploads/" + rawId);
    }

    @Test
    void scheduledCleanupReportsMixedPhysicalDeleteOutcomeAsCountsOnly() {
        String failedId = "scheduled-private-failed-id";
        String deletedId = "scheduled-private-deleted-id";
        seed(service, failedId, 0L);
        seed(service, deletedId, 0L);
        when(storage.delete("/uploads/" + failedId)).thenReturn(false);
        when(storage.delete("/uploads/" + deletedId)).thenReturn(true);

        service.evictExpiredAttachments();

        assertAll(
                () -> assertEquals(2,
                        TraceStore.get("attachment.retention.metadataEvictedCount")),
                () -> assertEquals(1,
                        TraceStore.get("attachment.retention.physicalDeletedCount")),
                () -> assertEquals(1,
                        TraceStore.get("attachment.retention.physicalDeleteFailedCount")));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(failedId));
        assertFalse(trace.contains(deletedId));
        verify(storage).delete("/uploads/" + failedId);
        verify(storage).delete("/uploads/" + deletedId);
    }

    private static AttachmentService serviceWithTtl(
            LocalFileStorageService storage,
            String ttl) {
        AttachmentService candidate = new AttachmentService(storage, new FileIngestionService());
        ReflectionTestUtils.setField(candidate, "environment",
                new MockEnvironment().withProperty("attachments.retention.ttl-ms", ttl));
        return candidate;
    }

    private static void seed(AttachmentService target, String id, long retainedAt) {
        map(target, "repo", AttachmentDto.class).put(id,
                new AttachmentDto(id, id + ".txt", 1L, "text/plain", "/uploads/" + id));
        map(target, "retainedAtEpochMsById", Long.class).put(id, retainedAt);
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> map(
            AttachmentService target,
            String field,
            Class<T> ignoredType) {
        return (Map<String, T>) Objects.requireNonNull(
                ReflectionTestUtils.getField(target, field), field);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> sessionIndex(AttachmentService target) {
        return (Map<String, List<String>>) Objects.requireNonNull(
                ReflectionTestUtils.getField(target, "sessionIndex"), "sessionIndex");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for deterministic test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for deterministic test latch", interrupted);
        }
    }

    private static void awaitDelegationOrCompletion(
            CountDownLatch delegation,
            Future<?> upload) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (delegation.getCount() > 0L
                && !upload.isDone()
                && System.nanoTime() < deadline) {
            delegation.await(10, TimeUnit.MILLISECONDS);
        }
        assertTrue(delegation.getCount() == 0L || upload.isDone(),
                "registration neither completed nor reached the delegated interleaving point");
    }

    private static final class PausingDelegationAttachmentService extends AttachmentService {
        private final AtomicBoolean oneArgumentCalled = new AtomicBoolean();
        private final CountDownLatch oneArgumentReturned = new CountDownLatch(1);
        private final CountDownLatch releaseOneArgumentReturn = new CountDownLatch(1);

        private PausingDelegationAttachmentService(LocalFileStorageService storage) {
            super(storage, new FileIngestionService());
        }

        @Override
        public List<AttachmentDto> saveAll(List<MultipartFile> files) {
            oneArgumentCalled.set(true);
            List<AttachmentDto> saved = super.saveAll(files);
            oneArgumentReturned.countDown();
            await(releaseOneArgumentReturn);
            return saved;
        }
    }

    private static final class BlockingRemoveMap<K, V> extends ConcurrentHashMap<K, V> {
        private final K blockedKey;
        private final AtomicBoolean blockOnce = new AtomicBoolean();
        private final CountDownLatch removeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRemove = new CountDownLatch(1);

        private BlockingRemoveMap(K blockedKey) {
            this.blockedKey = blockedKey;
        }

        @Override
        public V remove(Object key) {
            V removed = super.remove(key);
            if (Objects.equals(blockedKey, key) && blockOnce.compareAndSet(false, true)) {
                removeEntered.countDown();
                await(releaseRemove);
            }
            return removed;
        }
    }

    private static final class BlockingConditionalRemoveMap<K, V> extends ConcurrentHashMap<K, V> {
        private final K blockedKey;
        private final AtomicBoolean blockOnce = new AtomicBoolean();
        private final CountDownLatch removeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRemove = new CountDownLatch(1);

        private BlockingConditionalRemoveMap(K blockedKey) {
            this.blockedKey = blockedKey;
        }

        @Override
        public boolean remove(Object key, Object value) {
            if (Objects.equals(blockedKey, key) && blockOnce.compareAndSet(false, true)) {
                removeEntered.countDown();
                await(releaseRemove);
            }
            return super.remove(key, value);
        }
    }
}
