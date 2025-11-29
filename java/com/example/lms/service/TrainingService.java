package com.example.lms.service;

import com.example.lms.domain.TrainingJob;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.domain.TranslationSample;
import com.example.lms.domain.enums.TranslationRoute;
import com.example.lms.repository.MemoryRepository;
import com.example.lms.repository.SampleRepository;
import com.example.lms.repository.TrainingJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
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

    // Implementation shim: implement the similarity measurement service using the actual computation.
    // private final SimilarityService similarityService;

    private static final int PAGE_SIZE = 1000; // 한 번에 처리할 데이터 양

    /** 학습 시작 → Job ID 반환 */
    public Long startTraining() {
        TrainingJob job = new TrainingJob();
        job.setStartedAt(LocalDateTime.now());
        job.setStatus("RUNNING");
        job.setTotal(sampleRepo.count());
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
                page = sampleRepo.findAll(pageable);
                for (TranslationSample sample : page.getContent()) {
                    processed++;
                    TranslationMemory tm = memoryRepo.findBySourceHash(sample.getSourceHash())
                            .orElseGet(() -> new TranslationMemory(sample.getSourceHash()));

                    // =======================================================================
                    //      ▼▼▼ [고도화] 정교화된 보상 함수(Reward Function) 적용 ▼▼▼
                    // =======================================================================

                    double similarityScore = 0.95; // 임시값

                    double newReward = calculateReward(sample, similarityScore);

                    tm.setCorrected(sample.getTranslated());
                    tm.setCosineSimilarity(similarityScore);

                    int n = tm.getHitCount();
                    double currentMeanReward = tm.getRewardMean();
                    double newMeanReward = (currentMeanReward * n + newReward) / (n + 1);

                    tm.setRewardMean(newMeanReward);
                    tm.setQValue(newMeanReward);
                    tm.setHitCount(n + 1);

                    memoryRepo.save(tm);
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

    private double calculateReward(TranslationSample sample, double similarityScore) {
        boolean wasCorrectedByUser = StringUtils.hasText(sample.getCorrected());
        if (wasCorrectedByUser) {
            log.debug("사용자 수정 감지 [hash:{}]. 낮은 보상(0.1) 적용.", sample.getSourceHash());
            return 0.1;
        }

        double qualityReward = similarityScore;
        double costFactor = getCostFactorFor(sample.getRoute());
        double finalReward = qualityReward * costFactor;

        return Math.max(0.0, Math.min(1.0, finalReward));
    }

    /**
     * [수정] 컴파일러 호환성 문제를 해결하기 위해 if-else if 구문으로 변경
     */
    private double getCostFactorFor(TranslationRoute route) {
        if (route == null) {
            return 0.8;
        }

        if (route == TranslationRoute.MEMORY) {
            return 1.0;
        } else if (route == TranslationRoute.GPT_3_5 || route == TranslationRoute.GEMINI) {
            return 0.95;
        } else if (route == TranslationRoute.GPT_4) {
            return 0.85;
        } else {
            return 0.9;
        }
    }
}