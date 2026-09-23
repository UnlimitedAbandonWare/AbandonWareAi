package com.example.lms.plugin.image.jobs;

import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ImageManifestWriter {
    private static final Logger log = LoggerFactory.getLogger(ImageManifestWriter.class);

    private final ObjectMapper mapper = new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${image.jobs.manifest-dir:${IMAGE_JOB_MANIFEST_DIR:var/abnadon/images}}")
    private String manifestDir;

    public String write(ImageJob job) {
        if (job == null || job.getId() == null || job.getId().isBlank()) {
            return null;
        }
        Path temporary = null;
        try {
            Path file = manifestPath(job.getId());
            Path parent = file.getParent();
            if (parent == null) {
                throw new IOException("image-manifest-parent-unavailable");
            }
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, temporaryPrefix(file), ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest(job));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveIntoPlace(temporary, file);
            temporary = null;
            return publicManifestUrl(job.getId());
        } catch (Exception ignore) {
            log.debug("Image manifest write skipped jobIdHash={} jobIdLength={} errorType={}",
                    SafeRedactor.hashValue(job.getId()), job.getId().length(), ignore.getClass().getSimpleName());
            return null;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception cleanupError) {
                    log.debug("Image manifest cleanup skipped jobIdHash={} jobIdLength={} errorType={}",
                            SafeRedactor.hashValue(job.getId()), job.getId().length(),
                            cleanupError.getClass().getSimpleName());
                }
            }
        }
    }

    public Map<String, Object> read(ImageJob job) {
        if (job == null || job.getId() == null || job.getId().isBlank()) {
            return Map.of();
        }
        try {
            Path file = manifestPath(job.getId());
            if (Files.exists(file)) {
                return mapper.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception ignore) {
            log.debug("Image manifest read skipped jobIdHash={} jobIdLength={} errorType={}",
                    SafeRedactor.hashValue(job.getId()), job.getId().length(), ignore.getClass().getSimpleName());
        }
        return manifest(job);
    }

    private Map<String, Object> manifest(ImageJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.getId());
        out.put("status", job.getStatus() == null ? null : job.getStatus().name());
        out.put("publicUrl", job.getPublicUrl());
        out.put("artifactHash", job.getArtifactHash());
        out.put("bytes", job.getBytes());
        out.put("contentType", job.getContentType());
        out.put("progress", job.getProgress());
        out.put("reason", SafeRedactor.traceLabelOrFallback(job.getReason(), "unknown"));
        out.put("promptHash", SafeRedactor.hashValue(job.getPrompt() == null ? "" : job.getPrompt()));
        out.put("createdAt", instant(job.getCreatedAt()));
        out.put("startedAt", instant(job.getStartedAt()));
        out.put("completedAt", instant(job.getCompletedAt()));
        out.put("durationMs", job.getDurationMs());
        return out;
    }

    private Path manifestPath(String jobId) {
        String dir = manifestDir == null || manifestDir.isBlank() ? "var/abnadon/images" : manifestDir.trim();
        String safeId = jobId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return Path.of(dir).resolve(safeId + ".json");
    }

    private static String temporaryPrefix(Path target) {
        Path fileName = target.getFileName();
        String prefix = "." + (fileName == null ? "image-manifest" : fileName) + ".";
        return prefix.length() >= 3 ? prefix : "manifest.";
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String publicManifestUrl(String jobId) {
        return "/api/image-plugin/jobs/" + jobId + "/manifest";
    }

    private static String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
