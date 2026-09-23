package com.example.lms.plugin.image;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageJobServiceSourceContractTest {

    @Test
    void storageHintsDoNotUseUserPrompt() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/plugin/image/jobs/ImageJobService.java"));

        assertTrue(source.contains("IMAGE_STORAGE_HINT"));
        assertFalse(source.contains("storage.saveBase64Png(b64, job.getPrompt())"));
        assertFalse(source.contains("storage.downloadToStorage(first, job.getPrompt())"));
    }

    @Test
    void cleanupAndArtifactMetadataFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/plugin/image/jobs/ImageJobService.java"));

        assertFalse(source.contains("Image job metadata cleanup skipped jobId={} errorType={}"));
        assertFalse(source.contains("Image job artifact metadata skipped jobId={} pathRef={} errorType={}"));
        assertTrue(source.contains("Image job metadata cleanup skipped jobIdHash={} jobIdLength={} errorType={}"));
        assertTrue(source.contains("Image job artifact metadata skipped jobIdHash={} jobIdLength={} pathRef={} errorType={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(job.getId())"));
        assertTrue(source.contains("storagePathReference(stored.absolutePath())"));
    }
}
