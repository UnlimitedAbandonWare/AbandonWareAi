package com.example.lms.plugin.image.jobs;

import com.example.lms.image.ImageMetaHolder;
import com.example.lms.plugin.image.OpenAiImageService;
import com.example.lms.plugin.image.debug.ImageJobDebugAgent;
import com.example.lms.plugin.image.debug.ImageJobDebugLedger;
import com.example.lms.plugin.image.storage.FileSystemImageStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageJobServiceDebugDiagnosticsTest {

    private static final long WAIT_SECONDS = 5L;

    @AfterEach
    void clearMeta() {
        ImageMetaHolder.clear();
    }

    @Test
    void processNextPreservesProviderReasonAndRecordsVerdictSignal() {
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        FileSystemImageStorage storage = mock(FileSystemImageStorage.class);
        ImageJobProperties props = new ImageJobProperties();
        props.setRelayDelayMs(300_000L);
        props.setEtaSamples(10);
        ImageManifestWriter manifestWriter = mock(ImageManifestWriter.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        ImageJobService service = new ImageJobService(jobRepo, imageService, storage, props, manifestWriter);
        ReflectionTestUtils.setField(service, "debugLedger", ledger);

        ImageJob job = new ImageJob();
        job.setId("job-1");
        job.setPrompt("private prompt should stay out of debug output");
        job.setSize("1024x1024");
        job.setStatus(ImageJob.Status.PENDING);
        job.setCreatedAt(Instant.now().minusSeconds(400));

        when(jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING)).thenReturn(job);
        when(imageService.generateImages(anyString(), anyInt(), any())).thenAnswer(invocation -> {
            ImageMetaHolder.put("image.error", "OPENAI_429");
            return List.of();
        });

        service.processNext();

        assertEquals(ImageJob.Status.FAILED, job.getStatus());
        assertEquals("OPENAI_429", job.getReason());
        verify(ledger, atLeastOnce()).record(anyString(), any(ImageJobDebugAgent.class), anyString(),
                anyDouble(), anyDouble(), anyDouble(), anyString(), any(Map.class));
        String dump = job + "";
        assertFalse(dump.contains("OPENAI_API_KEY"));
    }

    @Test
    void processNextRecordsStorageAndManifestSignalsWithoutRawPath() throws Exception {
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        FileSystemImageStorage storage = mock(FileSystemImageStorage.class);
        ImageJobProperties props = new ImageJobProperties();
        props.setEtaSamples(10);
        ImageManifestWriter manifestWriter = mock(ImageManifestWriter.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        ImageJobService service = new ImageJobService(jobRepo, imageService, storage, props, manifestWriter);
        ReflectionTestUtils.setField(service, "debugLedger", ledger);

        ImageJob job = new ImageJob();
        job.setId("job-2");
        job.setPrompt("private prompt");
        job.setSize("1024x1024");
        job.setStatus(ImageJob.Status.PENDING);
        job.setCreatedAt(Instant.now());
        String absolutePath = "C:\\Users\\nninn\\Pictures\\private-output.png";

        when(jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING)).thenReturn(job);
        when(imageService.generateImages(anyString(), anyInt(), any())).thenReturn(List.of("data:image/png;base64,aW1n"));
        when(storage.saveBase64Png(anyString(), anyString()))
                .thenReturn(new FileSystemImageStorage.Stored(absolutePath, "/generated-images/private-output.png"));

        service.processNext();

        assertEquals(ImageJob.Status.SUCCEEDED, job.getStatus());
        verify(ledger, atLeastOnce()).record(anyString(), any(ImageJobDebugAgent.class), anyString(),
                anyDouble(), anyDouble(), anyDouble(), anyString(), any(Map.class));
        assertFalse(String.valueOf(job.getFilePath()).contains(absolutePath));
        assertTrue(String.valueOf(job.getFilePath()).contains("pathHash="));
    }

    @Test
    void processNextFailsUnstoredProviderCandidateWithoutProviderOkVerdict() throws Exception {
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        FileSystemImageStorage storage = mock(FileSystemImageStorage.class);
        ImageJobProperties props = new ImageJobProperties();
        props.setEtaSamples(10);
        ImageManifestWriter manifestWriter = mock(ImageManifestWriter.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        ImageJobService service = new ImageJobService(jobRepo, imageService, storage, props, manifestWriter);
        ReflectionTestUtils.setField(service, "debugLedger", ledger);

        ImageJob job = new ImageJob();
        job.setId("job-unstored-candidate");
        job.setPrompt("private prompt");
        job.setSize("1024x1024");
        job.setStatus(ImageJob.Status.PENDING);
        job.setCreatedAt(Instant.now());

        when(jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING)).thenReturn(job);
        when(imageService.generateImages(anyString(), anyInt(), any()))
                .thenReturn(List.of("https://cdn.example.invalid/generated.png"));
        when(storage.downloadToStorage(anyString(), anyString())).thenReturn(null);

        service.processNext();

        assertEquals(ImageJob.Status.FAILED, job.getStatus());
        assertEquals("STORAGE_EMPTY", job.getReason());

        ArgumentCaptor<ImageJobDebugAgent> agentCaptor = ArgumentCaptor.forClass(ImageJobDebugAgent.class);
        ArgumentCaptor<String> stageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledger, atLeastOnce()).record(eq("job-unstored-candidate"), agentCaptor.capture(), stageCaptor.capture(),
                anyDouble(), anyDouble(), anyDouble(), reasonCaptor.capture(), any(Map.class));

        boolean providerOk = false;
        boolean failedVerdict = false;
        List<ImageJobDebugAgent> agents = agentCaptor.getAllValues();
        List<String> stages = stageCaptor.getAllValues();
        List<String> reasons = reasonCaptor.getAllValues();
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i) == ImageJobDebugAgent.PROVIDER
                    && "provider.call.end".equals(stages.get(i))
                    && "OK".equals(reasons.get(i))) {
                providerOk = true;
            }
            if (agents.get(i) == ImageJobDebugAgent.VERDICT
                    && "job.failed.unusable_artifact".equals(stages.get(i))
                    && "STORAGE_EMPTY".equals(reasons.get(i))) {
                failedVerdict = true;
            }
        }
        assertFalse(providerOk);
        assertTrue(failedVerdict);
    }

    @Test
    void estimateUsesStableDurationSnapshotDuringScheduledAppend() throws Exception {
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        FileSystemImageStorage storage = mock(FileSystemImageStorage.class);
        ImageJobProperties props = new ImageJobProperties();
        props.setRelayDelayMs(1_000L);
        props.setEtaSamples(10);
        ImageManifestWriter manifestWriter = mock(ImageManifestWriter.class);
        ImageJobService service = new ImageJobService(jobRepo, imageService, storage, props, manifestWriter);
        LatchControlledDurations durations = new LatchControlledDurations(List.of(1_000L, 2_000L));
        ReflectionTestUtils.setField(service, "recentDurations", durations);

        ImageJob etaJob = new ImageJob();
        etaJob.setId("eta-target");
        etaJob.setStatus(ImageJob.Status.PENDING);
        etaJob.setCreatedAt(Instant.now());

        ImageJob writerJob = new ImageJob();
        writerJob.setId("duration-writer");
        writerJob.setPrompt("private prompt");
        writerJob.setSize("1024x1024");
        writerJob.setStatus(ImageJob.Status.PENDING);
        writerJob.setCreatedAt(Instant.now());

        CountDownLatch writerBeforeAppend = new CountDownLatch(1);
        AtomicInteger saveCount = new AtomicInteger();
        when(jobRepo.findById("eta-target")).thenReturn(Optional.of(etaJob));
        when(jobRepo.findAll()).thenReturn(List.of(etaJob));
        when(jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING)).thenReturn(writerJob);
        when(imageService.generateImages(anyString(), anyInt(), any())).thenReturn(List.of());
        when(jobRepo.save(any(ImageJob.class))).thenAnswer(invocation -> {
            if (saveCount.incrementAndGet() == 2) {
                writerBeforeAppend.countDown();
            }
            return invocation.getArgument(0);
        });

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ImageJobService.Eta> estimate = workers.submit(() -> service.estimate("eta-target"));
            assertTrue(durations.readerPaused.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "ETA reader should pause inside duration traversal");

            Future<?> writer = workers.submit(service::processNext);
            assertTrue(writerBeforeAppend.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "scheduled writer should reach the duration append boundary");
            durations.appendCompleted.await(500L, TimeUnit.MILLISECONDS);
            durations.releaseReader.countDown();

            ImageJobService.Eta eta = assertDoesNotThrow(
                    () -> estimate.get(WAIT_SECONDS, TimeUnit.SECONDS),
                    "ETA must use a stable duration view while the scheduler appends");
            assertDoesNotThrow(() -> writer.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(2L, eta.etaSeconds());
            assertTrue(eta.expectedReadyAt() != null && !eta.expectedReadyAt().isBlank());
        } finally {
            durations.releaseReader.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private static final class LatchControlledDurations extends LinkedList<Long> {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch readerPaused = new CountDownLatch(1);
        private final CountDownLatch releaseReader = new CountDownLatch(1);
        private final CountDownLatch appendCompleted = new CountDownLatch(1);
        private final AtomicBoolean pauseClaimed = new AtomicBoolean();

        private LatchControlledDurations(List<Long> seed) {
            super.addAll(seed);
        }

        @Override
        public Spliterator<Long> spliterator() {
            Spliterator<Long> delegate = super.spliterator();
            return new Spliterator<>() {
                @Override
                public boolean tryAdvance(Consumer<? super Long> action) {
                    return delegate.tryAdvance(value -> {
                        pauseReaderOnce();
                        action.accept(value);
                    });
                }

                @Override
                public Spliterator<Long> trySplit() {
                    return null;
                }

                @Override
                public long estimateSize() {
                    return delegate.estimateSize();
                }

                @Override
                public int characteristics() {
                    return delegate.characteristics();
                }
            };
        }

        @Override
        public Object[] toArray() {
            pauseReaderOnce();
            return super.toArray();
        }

        @Override
        public void addLast(Long duration) {
            super.addLast(duration);
            appendCompleted.countDown();
        }

        private void pauseReaderOnce() {
            if (!pauseClaimed.compareAndSet(false, true)) {
                return;
            }
            readerPaused.countDown();
            try {
                if (!releaseReader.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("duration reader release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("duration reader was interrupted", interrupted);
            }
        }
    }
}
