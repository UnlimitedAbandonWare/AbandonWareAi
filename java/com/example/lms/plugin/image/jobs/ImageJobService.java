package com.example.lms.plugin.image.jobs;

import com.example.lms.image.ImageMetaHolder;
import com.example.lms.plugin.image.OpenAiImageService;
import com.example.lms.plugin.image.storage.FileSystemImageStorage;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import java.time.Instant;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
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

    private final ImageJobRepository jobRepo;
    private final OpenAiImageService imageService;
    private final FileSystemImageStorage storage;
    private final ImageJobProperties props;

    // Recent job durations (in milliseconds) for ETA estimation
    private final Deque<Long> recentDurations = new LinkedList<>();

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
        ImageJob job = new ImageJob();
        job.setId(UUID.randomUUID().toString());
        job.setPrompt(prompt);
        job.setModel(model);
        job.setSize(size);
        job.setSessionId(sessionId);
        job.setStatus(ImageJob.Status.PENDING);
        job.setCreatedAt(Instant.now());
        jobRepo.save(job);
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
        long avgMs;
        if (recentDurations.isEmpty()) {
            avgMs = relayMs;
        } else {
            avgMs = (long) recentDurations.stream().mapToLong(Long::longValue).average().orElse(relayMs);
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
        jobRepo.save(job);
        try {
            // Always request a single image; the count is set internally
            List<String> urls = imageService.generateImages(job.getPrompt(), 1, job.getSize());
            if (urls != null && !urls.isEmpty()) {
                String first = urls.get(0);
                FileSystemImageStorage.Stored stored;
                if (first != null && first.startsWith("data:image")) {
                    int comma = first.indexOf(',');
                    String b64 = (comma >= 0) ? first.substring(comma + 1) : first;
                    stored = storage.saveBase64Png(b64, job.getPrompt());
                } else {
                    stored = storage.downloadToStorage(first, job.getPrompt());
                }
                job.setFilePath(stored.absolutePath());
                job.setPublicUrl(stored.publicUrl());
                job.setStatus(ImageJob.Status.SUCCEEDED);
            } else {
                job.setStatus(ImageJob.Status.FAILED);
                job.setReason("NO_RESULT");
            }
        } catch (Exception e) {
            log.warn("Image job {} failed: {}", job.getId(), e.toString());
            job.setStatus(ImageJob.Status.FAILED);
            job.setReason(e.toString());
        } finally {
            job.setCompletedAt(Instant.now());
            long duration = System.currentTimeMillis() - startMillis;
            job.setDurationMs(duration);
            jobRepo.save(job);
            // Maintain a moving window of recent durations for ETA estimation
            recentDurations.addLast(duration);
            if (recentDurations.size() > props.getEtaSamples()) {
                recentDurations.removeFirst();
            }
            // Clear any image meta to prevent leakage into subsequent calls
            try {
                ImageMetaHolder.clear();
            } catch (Exception ignore) {
                // ignore cleanup failures
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
}