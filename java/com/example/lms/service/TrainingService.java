package com.example.lms.service;

import com.example.lms.domain.TrainingJob;
import com.example.lms.domain.TranslationSample;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.MemoryRepository;
import com.example.lms.repository.SampleRepository;
import com.example.lms.repository.TrainingJobRepository;
import com.example.lms.service.reinforcement.RewardScoringEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




@Service
@RequiredArgsConstructor
public class TrainingService {
    private static final Logger log = LoggerFactory.getLogger(TrainingService.class);

    private final SampleRepository sampleRepo;
    private final MemoryRepository memoryRepo;
    private final TrainingJobRepository jobRepo;

    // Unified reward policy (single entrypoint).
    private final RewardScoringEngine rewardEngine = RewardScoringEngine.DEFAULT;

    // Implementation shim: implement the similarity measurement service using the actual computation.
    // private final SimilarityService similarityService;

    private static final int PAGE_SIZE = 500; // 한 번에 처리할 데이터 양 (JPA batch + flush friendly)

    /** 학습 시작 → Job ID 반환 */
    public Long startTraining() {
        TrainingJob job = new TrainingJob();
        job.setStartedAt(LocalDateTime.now());
        job.setStatus("RUNNING");
        job.setTotal(sampleRepo.countByCorrectedIsNotNullAndTrainedAtIsNull());
        jobRepo.save(job);

        // 비동기 수행
        trainAsync(job.getId());
        return job.getId();
    }

    /** Job 진행상태 조회 */
    public TrainingJob status(Long id) {
        return jobRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("없는 Job id"));
    }

    @Async
    @Transactional
    protected CompletableFuture<Void> trainAsync(Long jobId) {
        TrainingJob job = jobRepo.findById(jobId).orElseThrow();
        try {
            long processed = 0;
            Pageable pageable = PageRequest.of(0, PAGE_SIZE);

            Page<TranslationSample> page;
            do {
                // Train only "valuable" samples: user-corrected and not yet trained.
                page = sampleRepo.findByCorrectedIsNotNullAndTrainedAtIsNull(pageable);
                List<TranslationSample> samples = page.getContent();
                if (samples == null || samples.isEmpty()) {
                    pageable = page.nextPageable();
                    continue;
                }

                // N+1 제거: bulk load translation_memory by source_hash
                Set<String> hashes = new LinkedHashSet<>();
                for (TranslationSample s : samples) {
                    if (s != null && s.getSourceHash() != null && !s.getSourceHash().isBlank()) {
                        hashes.add(s.getSourceHash());
                    }
                }

                Map<String, TranslationMemory> memByHash = new HashMap<>();
                if (!hashes.isEmpty()) {
                    for (TranslationMemory m : memoryRepo.findBySourceHashIn(hashes)) {
                        if (m != null && m.getSourceHash() != null) {
                            memByHash.put(m.getSourceHash(), m);
                        }
                    }
                }

                LocalDateTime trainedAt = LocalDateTime.now();
                List<TranslationMemory> toSaveMem = new ArrayList<>(samples.size());
                List<TranslationSample> toSaveSamples = new ArrayList<>(samples.size());

                for (TranslationSample sample : samples) {
                    if (sample == null) continue;

                    processed++;

                    String h = sample.getSourceHash();
                    if (h == null || h.isBlank()) continue;

                    TranslationMemory tm = memByHash.get(h);
                    if (tm == null) {
                        tm = new TranslationMemory(h);
                        memByHash.put(h, tm);
                    }

                    // Similarity placeholder 제거:
                    // 1) sample.similarity 사용
                    // 2) 없으면 qError 기반 대체: similarity = 1 - clamp(qError)
                    double similarityScore = resolveSimilarity(sample);

                    // Reward policy 단일화: RewardScoringEngine에 위임
                    double reward = rewardEngine.score(tm, sample.getSourceText(), similarityScore);

                    // Apply learning signal to memory
                    // - corrected: 사람이 교정한 결과만 반영
                    tm.setCorrected(sample.getCorrected());
                    tm.setCosineSimilarity(similarityScore);
                    tm.applyReward(reward);

                    // Mark sample as trained (idempotent)
                    sample.setTrainedAt(trainedAt);

                    toSaveMem.add(tm);
                    toSaveSamples.add(sample);
                }

                // batch-friendly writes
                if (!toSaveMem.isEmpty()) {
                    memoryRepo.saveAll(toSaveMem);
                    memoryRepo.flush();
                }
                if (!toSaveSamples.isEmpty()) {
                    sampleRepo.saveAll(toSaveSamples);
                    sampleRepo.flush();
                }

                if (processed % (PAGE_SIZE * 5) == 0) {
                    job.setProcessed(processed);
                    jobRepo.save(job);
                    log.info("/* ... *&#47; Job #{} 진행 중: {} / {}", jobId, processed, job.getTotal());
                }
                pageable = page.nextPageable();

            } while (page.hasNext());


            job.setProcessed(processed);
            job.setStatus("COMPLETED");
            job.setFinishedAt(LocalDateTime.now());
            jobRepo.save(job);
            log.info("Training job #{} 완료 ({} 건)", jobId, processed);

        } catch (Exception e) {
            log.error("Training 실패", e);
            job.setStatus("FAILED");
            job.setMessage(e.getMessage());
            job.setFinishedAt(LocalDateTime.now());
            jobRepo.save(job);
        }
        return CompletableFuture.completedFuture(null);
    }

    private static double resolveSimilarity(TranslationSample sample) {
        if (sample == null) return 0.0;
        Double s = sample.getSimilarity();
        if (s != null && !s.isNaN() && !s.isInfinite()) {
            return clamp01(s);
        }
        Double qErr = sample.getQError();
        if (qErr != null && !qErr.isNaN() && !qErr.isInfinite()) {
            return clamp01(1.0 - clamp01(qErr));
        }
        return 0.0;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}