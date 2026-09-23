package com.example.lms.plugin.image.jobs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.image.ImageMetaHolder;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.plugin.image.OpenAiImageService;
import com.example.lms.plugin.image.storage.FileSystemImageStorage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageJobServicePathRedactionTest {

    @Test
    void storagePathReferenceDoesNotPersistRawAbsolutePath() {
        String absolutePath = "C:\\Users\\nninn\\Pictures\\AbandonWare\\img\\2026-06-05\\private.png";

        String reference = ImageJobService.storagePathReference(absolutePath);

        assertFalse(reference.contains(absolutePath));
        assertFalse(reference.contains("Users\\nninn"));
        assertTrue(reference.contains("pathHash=" + SafeRedactor.hashValue(absolutePath)));
        assertTrue(reference.contains("pathLength=" + absolutePath.length()));
    }

    @Test
    void processNextStoresRedactedFilePathReference() throws Exception {
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        FileSystemImageStorage storage = mock(FileSystemImageStorage.class);
        ImageJobProperties props = new ImageJobProperties();
        props.setEtaSamples(10);
        ImageManifestWriter manifestWriter = mock(ImageManifestWriter.class);
        ImageJobService service = new ImageJobService(jobRepo, imageService, storage, props, manifestWriter);

        ImageJob job = new ImageJob();
        job.setId("job-1");
        job.setPrompt("private image prompt");
        job.setSize("1024x1024");
        job.setStatus(ImageJob.Status.PENDING);
        job.setCreatedAt(Instant.now());

        String absolutePath = "C:\\Users\\nninn\\Pictures\\AbandonWare\\img\\2026-06-05\\private.png";
        when(jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING)).thenReturn(job);
        when(imageService.generateImages(anyString(), anyInt(), any())).thenReturn(List.of("data:image/png;base64,aW1hZ2U="));
        when(storage.saveBase64Png(anyString(), anyString()))
                .thenReturn(new FileSystemImageStorage.Stored(absolutePath, "/generated-images/2026-06-05/private.png"));

        service.processNext();

        assertFalse(job.getFilePath().contains(absolutePath));
        assertFalse(job.getFilePath().contains("Users\\nninn"));
        assertTrue(job.getFilePath().contains("pathHash=" + SafeRedactor.hashValue(absolutePath)));
        assertTrue(job.getFilePath().contains("pathLength=" + absolutePath.length()));
        verify(jobRepo, atLeastOnce()).save(job);
    }

    @Test
    void artifactMetadataFallbackLogDoesNotExposeRawJobId() throws Exception {
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        FileSystemImageStorage storage = mock(FileSystemImageStorage.class);
        ImageJobProperties props = new ImageJobProperties();
        props.setEtaSamples(10);
        ImageManifestWriter manifestWriter = mock(ImageManifestWriter.class);
        ImageJobService service = new ImageJobService(jobRepo, imageService, storage, props, manifestWriter);

        ImageJob job = new ImageJob();
        job.setId("raw-private-job-id");
        job.setPrompt("private image prompt");
        job.setSize("1024x1024");
        job.setStatus(ImageJob.Status.PENDING);
        job.setCreatedAt(Instant.now());

        Logger logger = (Logger) LoggerFactory.getLogger(ImageJobService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            when(jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING)).thenReturn(job);
            when(imageService.generateImages(anyString(), anyInt(), any())).thenReturn(List.of("data:image/png;base64,aW1hZ2U="));
            when(storage.saveBase64Png(anyString(), anyString()))
                    .thenReturn(new FileSystemImageStorage.Stored("C:\\Users\\nninn\\Pictures\\bad\u0000private.png",
                            "/generated-images/private.png"));

            service.processNext();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        String renderedLogs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(renderedLogs.contains("jobIdHash="));
        assertFalse(renderedLogs.contains("raw-private-job-id"));
    }

    @Test
    void waitTimeFallbackLogDoesNotExposeRawExceptionMessage() {
        ImageJob job = mock(ImageJob.class);
        when(job.getCreatedAt()).thenThrow(new IllegalStateException("ownerToken=private-token"));

        Logger logger = (Logger) LoggerFactory.getLogger(ImageJobService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            Long result = ReflectionTestUtils.invokeMethod(ImageJobService.class, "waitTimeMs", job);
            assertTrue(result == 0L);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        String renderedLogs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(renderedLogs.contains("[AWX][image-debug] wait time fallback"));
        assertTrue(renderedLogs.contains("errorType=IllegalStateException"));
        assertFalse(renderedLogs.contains("ownerToken"));
        assertFalse(renderedLogs.contains("private-token"));
    }

    @Test
    void providerReasonFallbackLogDoesNotExposeRawExceptionMessage() throws Exception {
        @SuppressWarnings("unchecked")
        ThreadLocal<Map<String, String>> meta = (ThreadLocal<Map<String, String>>) imageMetaThreadLocal();
        meta.set(new ThrowingStringMap());

        Logger logger = (Logger) LoggerFactory.getLogger(ImageJobService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            String reason = ReflectionTestUtils.invokeMethod(ImageJobService.class, "providerReasonOr", "NO_RESULT");
            assertEquals("NO_RESULT", reason);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            ImageMetaHolder.clear();
        }

        String renderedLogs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(renderedLogs.contains("[AWX][image-debug] provider reason fallback"));
        assertTrue(renderedLogs.contains("errorType=IllegalStateException"));
        assertFalse(renderedLogs.contains("ownerToken"));
        assertFalse(renderedLogs.contains("private-token"));
    }

    private static Object imageMetaThreadLocal() throws Exception {
        Field field = ImageMetaHolder.class.getDeclaredField("META");
        field.setAccessible(true);
        return field.get(null);
    }

    private static final class ThrowingStringMap extends HashMap<String, String> {
        @Override
        public String get(Object key) {
            throw new IllegalStateException("ownerToken=private-token");
        }
    }
}
