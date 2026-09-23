package com.example.lms.plugin.image.jobs;

import com.example.lms.image.ImageMetaHolder;
import com.example.lms.plugin.image.OpenAiImageService;
import com.example.lms.plugin.image.debug.ImageJobDebugAgent;
import com.example.lms.plugin.image.debug.ImageJobDebugLedger;
import com.example.lms.plugin.image.storage.FileSystemImageStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;




/**
 * Service coordinating the lifecycle of image generation jobs.  Jobs are
 * enqueued via {@link #enqueue(String, String, String, String)} and later
 * processed by a scheduled task at a fixed delay defined by
 * {@link ImageJobProperties#getRelayDelayMs()}.  During processing the
 * service delegates image generation to {@link OpenAiImageService} and
 * persists the resulting image using {@link FileSystemImageStorage}.  A
 * moving average of recent job durations is maintained to provide ETA
 * estimates for pending jobs.
 */
@Service
@ConditionalOnBean(com.example.lms.plugin.image.OpenAiImageService.class)
@RequiredArgsConstructor
public class ImageJobService {

    private static final Logger log = LoggerFactory.getLogger(ImageJobService.class);
    private static final String IMAGE_STORAGE_HINT = "openai-image";

    private final ImageJobRepository jobRepo;
    private final OpenAiImageService imageService;
    private final FileSystemImageStorage storage;
    private final ImageJobProperties props;
    private final ImageManifestWriter manifestWriter;

    @Autowired(required = false)
    private ImageJobDebugLedger debugLedger;

    // Recent job durations (in milliseconds) for ETA estimation
    private final Deque<Long> recentDurations = new LinkedList<>();
    private final Object recentDurationsLock = new Object();

    /**
     * Enqueue a new image generation request.  The job is persisted in
     * {@link ImageJob.Status#PENDING} state and will be processed on the
     * next scheduler tick.
     *
     * @param prompt   the image prompt
     * @param model    the image model (e.g. gpt-image-1)
     * @param size     the desired image size
     * @param sessionId optional session identifier used to group jobs
     * @return the persisted job
     */
    @Transactional
    public ImageJob enqueue(String prompt, String model, String size, String sessionId) {
        return enqueue(prompt, model, size, sessionId, null);
    }

    @Transactional
    public ImageJob enqueue(String prompt, String model, String size, String sessionId, String ownerKeyHash) {
        ImageJob job = new ImageJob();
        job.setId(UUID.randomUUID().toString());
        job.setPrompt(prompt);
        job.setModel(model);
        job.setSize(size);
        job.setSessionId(sessionId);
        job.setOwnerKeyHash(ownerKeyHash);
        job.setStatus(ImageJob.Status.PENDING);
        job.setProgress(0);
        job.setCreatedAt(Instant.now());
        job.setManifestUrl(manifestWriter.write(job));
        jobRepo.save(job);
        recordDebug(job, ImageJobDebugAgent.CONFIG_SENTINEL,
                "job.enqueued", 0.10, 1.0, 1.0, "QUEUED",
                debugData(
                        "promptLength", prompt == null ? 0 : prompt.length(),
                        "model", model,
                        "size", size,
                        "ownerKeyPresent", ownerKeyHash != null && !ownerKeyHash.isBlank(),
                        "manifestUrlPresent", job.getManifestUrl() != null));
        return job;
    }

    /**
     * Estimate how long until the specified job is expected to complete.
     * The ETA is computed by multiplying the job's position in the queue
     * (number of pending/in-progress jobs before it) by the relay delay
     * and adding the moving average of recent job durations.  When the
     * job has already completed (SUCCEEDED/FAILED) the ETA is zero.
     *
     * @param jobId the job identifier
     * @return an {@link Eta} record containing the ETA in seconds and an ISO timestamp
     */
    public Eta estimate(String jobId) {
        ImageJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) {
            return new Eta(0L, null);
        }
        if (job.getStatus() == ImageJob.Status.SUCCEEDED || job.getStatus() == ImageJob.Status.FAILED) {
            return new Eta(0L, null);
        }
        // Collect pending and in-progress jobs in FIFO order
        List<ImageJob> pending = jobRepo.findAll().stream()
                .filter(j -> j.getStatus() == ImageJob.Status.PENDING || j.getStatus() == ImageJob.Status.IN_PROGRESS)
                .sorted(Comparator.comparing(ImageJob::getCreatedAt))
                .toList();
        int index = -1;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).getId().equals(jobId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return new Eta(0L, null);
        }
        long relayMs = props.getRelayDelayMs();
        // Compute moving average duration; fallback to relay delay when no samples exist
        List<Long> durationSnapshot;
        synchronized (recentDurationsLock) {
            durationSnapshot = new ArrayList<>(recentDurations);
        }
        long avgMs;
        if (durationSnapshot.isEmpty()) {
            avgMs = relayMs;
        } else {
            avgMs = (long) durationSnapshot.stream().mapToLong(Long::longValue).average().orElse(relayMs);
        }
        long etaMs = relayMs * index + avgMs;
        long etaSeconds = (long) Math.ceil(etaMs / 1000.0);
        String iso = Instant.now().plusSeconds(etaSeconds).toString();
        return new Eta(etaSeconds, iso);
    }

    /**
     * Scheduled job processor.  Runs at a fixed delay and picks the
     * oldest pending job for execution.  If no pending jobs exist the
     * method simply returns.  Errors during processing are captured
     * and stored on the job entity.  Completed jobs update the
     * moving average used for ETA estimation.
     */
    // 더 안전: 단순 프로퍼티 플레이스홀더 사용
    @Scheduled(fixedDelayString = "${image.jobs.relay-delay-ms}")
    @Transactional
    public void processNext() {
        ImageJob job = jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING);
        if (job == null) {
            return;
        }
        long startMillis = System.currentTimeMillis();
        job.setStatus(ImageJob.Status.IN_PROGRESS);
        job.setStartedAt(Instant.now());
        job.setProgress(10);
        job.setManifestUrl(manifestWriter.write(job));
        jobRepo.save(job);
        long waitingMs = waitTimeMs(job);
        long relayMs = props.getRelayDelayMs();
        recordDebug(job, ImageJobDebugAgent.QUEUE_TIME,
                "process.start", waitingMs > relayMs ? 0.60 : 0.10,
                relayMs, waitingMs, "PROCESS_STARTED",
                debugData(
                        "waitingMs", waitingMs,
                        "relayDelayMs", relayMs,
                        "expectedUiPollMs", 120_000L,
                        "stale", waitingMs > Math.max(relayMs, 120_000L)));
        try {
            // Always request a single image; the count is set internally
            List<String> urls = imageService.generateImages(job.getPrompt(), 1, job.getSize());
            int returnedCount = urls == null ? 0 : urls.size();
            recordDebug(job, ImageJobDebugAgent.PROVIDER,
                    "provider.call.end", returnedCount > 0 ? 0.25 : 0.85,
                    1.0, returnedCount, returnedCount > 0 ? "RESULT_CANDIDATE" : providerReasonOr("NO_RESULT"),
                    debugData(
                            "returnedCount", returnedCount,
                            "providerErrorPresent", providerReasonOr(null) != null));
            if (urls != null && !urls.isEmpty()) {
                String first = urls.get(0);
                FileSystemImageStorage.Stored stored;
                if (first != null && first.startsWith("data:image")) {
                    int comma = first.indexOf(',');
                    String b64 = (comma >= 0) ? first.substring(comma + 1) : first;
                    stored = storage.saveBase64Png(b64, IMAGE_STORAGE_HINT);
                    recordDebug(job, ImageJobDebugAgent.STORAGE,
                            "storage.base64", 0.10, 1.0, 1.0, "BASE64_STORED",
                            debugData("base64Length", b64.length()));
                } else if (first != null && first.startsWith("/")) {
                    job.setPublicUrl(first);
                    job.setArtifactHash(hashLabel(first));
                    job.setStatus(ImageJob.Status.SUCCEEDED);
                    job.setProgress(100);
                    stored = null;
                    recordDebug(job, ImageJobDebugAgent.STORAGE,
                            "storage.local_url", 0.10, 1.0, 1.0, "LOCAL_URL",
                            debugData("publicUrlHash", com.example.lms.trace.SafeRedactor.hashValue(first)));
                } else {
                    stored = storage.downloadToStorage(first, IMAGE_STORAGE_HINT);
                    recordDebug(job, ImageJobDebugAgent.STORAGE,
                            "storage.remote_download", 0.15, 1.0, stored == null ? 0.0 : 1.0,
                            stored == null ? "STORAGE_EMPTY" : "REMOTE_STORED",
                            debugData("sourceUrlHash", com.example.lms.trace.SafeRedactor.hashValue(first)));
                }
                if (stored != null) {
                    job.setFilePath(storagePathReference(stored.absolutePath()));
                    job.setPublicUrl(stored.publicUrl());
                    populateArtifactMetadata(job, stored);
                    job.setStatus(ImageJob.Status.SUCCEEDED);
                    job.setProgress(100);
                    recordDebug(job, ImageJobDebugAgent.STORAGE,
                            "storage.artifact_metadata", 0.10, 1.0, 1.0, "ARTIFACT_READY",
                            debugData(
                                    "pathRef", storagePathReference(stored.absolutePath()),
                                    "publicUrlPresent", stored.publicUrl() != null,
                                    "bytes", job.getBytes(),
                                    "contentType", job.getContentType()));
                }
                if (job.getStatus() != ImageJob.Status.SUCCEEDED) {
                    String reason = providerReasonOr("STORAGE_EMPTY");
                    recordDebug(job, ImageJobDebugAgent.VERDICT,
                            "job.failed.unusable_artifact", 0.90, 1.0, 0.0, reason,
                            debugData(
                                    "returnedCount", returnedCount,
                                    "publicUrlPresent", job.getPublicUrl() != null,
                                    "artifactHashPresent", job.getArtifactHash() != null));
                    job.setStatus(ImageJob.Status.FAILED);
                    job.setProgress(100);
                    job.setReason(reason);
                }
            } else {
                String reason = providerReasonOr("NO_RESULT");
                recordDebug(job, ImageJobDebugAgent.VERDICT,
                        "job.failed.empty_result", 0.90, 1.0, 0.0, reason,
                        debugData("providerErrorPresent", !"NO_RESULT".equals(reason)));
                job.setStatus(ImageJob.Status.FAILED);
                job.setProgress(100);
                job.setReason(reason);
            }
        } catch (Exception e) {
            String reasonCode = imageFailureReason(e);
            recordDebug(job, ImageJobDebugAgent.VERDICT,
                    "job.failed.exception", 1.0, 1.0, 0.0, reasonCode,
                    debugData("exceptionType", e.getClass().getSimpleName()));
            log.warn("Image job {} failed reasonCode={}",
                    com.example.lms.trace.SafeRedactor.hashValue(job.getId()), reasonCode);
            job.setStatus(ImageJob.Status.FAILED);
            job.setProgress(100);
            job.setReason(reasonCode);
        } finally {
            job.setCompletedAt(Instant.now());
            long duration = System.currentTimeMillis() - startMillis;
            job.setDurationMs(duration);
            job.setManifestUrl(manifestWriter.write(job));
            recordDebug(job, ImageJobDebugAgent.MANIFEST,
                    "manifest.write", job.getManifestUrl() == null ? 0.75 : 0.10,
                    1.0, job.getManifestUrl() == null ? 0.0 : 1.0,
                    job.getManifestUrl() == null ? "MANIFEST_MISSING" : "OK",
                    debugData(
                            "status", job.getStatus() == null ? "UNKNOWN" : job.getStatus().name(),
                            "durationMs", duration,
                            "publicUrlPresent", job.getPublicUrl() != null,
                            "artifactHashPresent", job.getArtifactHash() != null));
            jobRepo.save(job);
            // Maintain a moving window of recent durations for ETA estimation
            int etaSamples = props.getEtaSamples();
            synchronized (recentDurationsLock) {
                recentDurations.addLast(duration);
                if (recentDurations.size() > etaSamples) {
                    recentDurations.removeFirst();
                }
            }
            // Clear any image meta to prevent leakage into subsequent calls
            try {
                ImageMetaHolder.clear();
            } catch (Exception ignore) {
                log.debug("Image job metadata cleanup skipped jobIdHash={} jobIdLength={} errorType={}",
                        com.example.lms.trace.SafeRedactor.hashValue(job.getId()),
                        job.getId() == null ? 0 : job.getId().length(),
                        ignore.getClass().getSimpleName());
            }
        }
    }

    /**
     * DTO representing an ETA.  When the ETA is zero the job has
     * completed and no expected completion timestamp is provided.
     *
     * @param etaSeconds      the estimated time remaining in seconds
     * @param expectedReadyAt ISO-8601 timestamp when the job is expected to complete
     */
    public record Eta(long etaSeconds, String expectedReadyAt) {}

    private static String imageFailureReason(Exception e) {
        if (e == null) {
            return "IMAGE_JOB_FAILED";
        }
        String name = e.getClass().getSimpleName();
        if (name == null || name.isBlank()) {
            return "IMAGE_JOB_FAILED";
        }
        String normalized = name.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(java.util.Locale.ROOT);
        return normalized.isBlank() ? "IMAGE_JOB_FAILED" : normalized;
    }

    private static void populateArtifactMetadata(ImageJob job, FileSystemImageStorage.Stored stored) {
        if (job == null || stored == null || stored.absolutePath() == null || stored.absolutePath().isBlank()) {
            return;
        }
        try {
            Path path = Path.of(stored.absolutePath());
            if (!Files.exists(path)) {
                return;
            }
            job.setBytes(Files.size(path));
            String contentType = Files.probeContentType(path);
            job.setContentType(contentType == null || contentType.isBlank() ? "image/png" : contentType);
            job.setArtifactHash(sha256(path));
        } catch (Exception ignore) {
            log.debug("Image job artifact metadata skipped jobIdHash={} jobIdLength={} pathRef={} errorType={}",
                    com.example.lms.trace.SafeRedactor.hashValue(job.getId()),
                    job.getId() == null ? 0 : job.getId().length(),
                    storagePathReference(stored.absolutePath()),
                    ignore.getClass().getSimpleName());
        }
    }

    static String storagePathReference(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return "pathHash=;pathLength=0";
        }
        return "pathHash=" + com.example.lms.trace.SafeRedactor.hashValue(absolutePath)
                + ";pathLength=" + absolutePath.length();
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] data = Files.readAllBytes(path);
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String hashLabel(String value) {
        return value == null || value.isBlank()
                ? null
                : com.example.lms.trace.SafeRedactor.hashValue(value);
    }

    private void recordDebug(ImageJob job,
                             ImageJobDebugAgent agent,
                             String stage,
                             double severity,
                             double expected,
                             double observed,
                             String reason,
                             Map<String, Object> data) {
        ImageJobDebugLedger ledger = debugLedger;
        if (ledger == null || job == null) {
            return;
        }
        try {
            ledger.record(job.getId(), agent, stage, severity, expected, observed, reason, data);
        } catch (Exception e) {
            log.debug("[AWX][image-debug] record skipped stage={} errorType={}",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                    e.getClass().getSimpleName());
        }
    }

    private static long waitTimeMs(ImageJob job) {
        if (job == null) {
            return 0L;
        }
        try {
            Instant createdAt = job.getCreatedAt();
            if (createdAt == null) {
                return 0L;
            }
            return Math.max(0L, Duration.between(createdAt, Instant.now()).toMillis());
        } catch (Exception ex) {
            log.debug("[AWX][image-debug] wait time fallback errorType={}",
                    ex.getClass().getSimpleName());
            return 0L;
        }
    }

    private static String providerReasonOr(String fallback) {
        try {
            String reason = ImageMetaHolder.get("image.error");
            return reason == null || reason.isBlank() ? fallback : reason;
        } catch (Exception ex) {
            log.debug("[AWX][image-debug] provider reason fallback errorType={}",
                    ex.getClass().getSimpleName());
            return fallback;
        }
    }

    private static Map<String, Object> debugData(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (kv == null) {
            return out;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) {
                continue;
            }
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }
}
