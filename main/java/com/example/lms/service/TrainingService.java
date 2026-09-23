package com.example.lms.service;

import com.example.lms.domain.TrainingJob;
import com.example.lms.domain.TranslationSample;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.MemoryRepository;
import com.example.lms.repository.SampleRepository;
import com.example.lms.repository.TrainingJobRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.reinforcement.RewardScoringEngine;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.LearningSampleValidationMetadata;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawDatasetWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




@Service
@RequiredArgsConstructor
public class TrainingService {
    private static final Logger log = LoggerFactory.getLogger(TrainingService.class);
    private static final ObjectMapper TRAINING_RAG_JSON = new ObjectMapper();

    private final SampleRepository sampleRepo;
    private final MemoryRepository memoryRepo;
    private final TrainingJobRepository jobRepo;
    private final TaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;

    // Unified reward policy (single entrypoint).
    private final RewardScoringEngine rewardEngine = RewardScoringEngine.DEFAULT;

    private UawDatasetWriter trainingRagDatasetWriter;
    private UawAutolearnProperties autolearnProperties = new UawAutolearnProperties();

    // Implementation shim: implement the similarity measurement service using the actual computation.
    // private final SimilarityService similarityService;

    private static final int PAGE_SIZE = 500; // 한 번에 처리할 데이터 양 (JPA batch + flush friendly)

    /** 학습 시작 → Job ID 반환 */
    @Autowired(required = false)
    void configureTrainingRag(UawDatasetWriter datasetWriter, UawAutolearnProperties props) {
        this.trainingRagDatasetWriter = datasetWriter;
        this.autolearnProperties = props == null ? new UawAutolearnProperties() : props;
    }

    public Long startTraining() {
        TrainingJob job = new TrainingJob();
        job.setStartedAt(LocalDateTime.now());
        job.setStatus("RUNNING");
        job.setTotal(sampleRepo.countByCorrectedIsNotNullAndTrainedAtIsNull());
        jobRepo.save(job);

        // 비동기 수행
        taskExecutor.execute(() -> transactionTemplate.executeWithoutResult(tx -> trainInternal(job.getId())));
        return job.getId();
    }

    /** Job 진행상태 조회 */
    public TrainingJob status(Long id) {
        return jobRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("없는 Job id"));
    }

    /**
     * Record an accepted RAG answer into the existing train_rag JSONL pipeline.
     * Callers should pass only PromptBuilder-backed final answers.
     */
    public void recordRagSample(String sessionId,
                                String query,
                                String answer,
                                List<String> citationUrls,
                                double qualityScore) {
        int evidenceCount = countPresent(citationUrls);
        boolean finalGate = clamp01(qualityScore) >= 0.70d && evidenceCount >= 1;
        boolean recorded = false;
        if (trainingRagDatasetWriter != null) {
            UawAutolearnProperties effective = effectiveAutolearnProperties();
            UawAutolearnProperties.Dataset dataset = effective.getDataset();
            String path = dataset == null ? "data/train_rag.jsonl" : dataset.getPath();
            String name = dataset == null ? "training-rag" : dataset.getName();
            recorded = trainingRagDatasetWriter.append(
                    new File(path == null || path.isBlank() ? "data/train_rag.jsonl" : path),
                    name == null || name.isBlank() ? "training-rag" : name,
                    query,
                    answer,
                    "training-rag",
                    evidenceCount,
                    sessionId,
                    trainingMetadata(evidenceCount, qualityScore, finalGate));
        }
        TraceStore.put("training.rag.recorded", recorded);
        TraceStore.put("training.rag.writerAvailable", trainingRagDatasetWriter != null);
        TraceStore.put("training.rag.sessionHash", SafeRedactor.hashValue(sessionId));
        TraceStore.put("training.rag.citationCount", evidenceCount);
        TraceStore.put("training.rag.qualityScore", clamp01(qualityScore));
    }

    public void recordFailurePattern(String sessionId,
                                     String failureType,
                                     String rawSlot,
                                     double boltzmannWeight) {
        TraceStore.put("training.cfvm.recorded", true);
        TraceStore.put("training.cfvm.sessionHash", SafeRedactor.hashValue(sessionId));
        TraceStore.put("training.cfvm.failureType", SafeRedactor.traceLabelOrFallback(failureType, "unknown"));
        TraceStore.put("training.cfvm.rawSlotHash", SafeRedactor.hashValue(rawSlot));
        TraceStore.put("training.cfvm.boltzmannWeight", clamp01(boltzmannWeight));
    }

    public void recordPlatePerformance(String plateId, boolean passed, double rewardScore) {
        TraceStore.put("training.plate.feedback", SafeRedactor.traceLabelOrFallback(plateId, "unknown"));
        TraceStore.put("training.plate.passed", passed);
        TraceStore.put("training.plate.rewardScore", clamp01(rewardScore));
    }

    public List<String> findTrainingRagSamples(String query, int limit) {
        String q = query == null ? "" : query.trim();
        int capped = Math.max(0, Math.min(limit, 5));
        TraceStore.put("training.rag.probe.queryHash", SafeRedactor.hashValue(q));
        TraceStore.put("training.rag.probe.requestedLimit", Math.max(0, limit));
        if (q.isBlank() || capped == 0) {
            traceTrainingProbe(false, "blank_query_or_limit", 0);
            return List.of();
        }

        Path datasetPath = trainingRagDatasetPath();
        TraceStore.put("training.rag.probe.datasetFileHash", SafeRedactor.hashValue(fileNameOf(datasetPath)));
        if (!Files.isRegularFile(datasetPath)) {
            traceTrainingProbe(false, "dataset_missing", 0);
            return List.of();
        }

        try (var lines = Files.lines(datasetPath, StandardCharsets.UTF_8)) {
            List<String> samples = lines
                    .map(TrainingService::sampleFromTrainingRagLine)
                    .filter(s -> !s.isBlank())
                    .filter(s -> matchesTrainingQuery(q, s))
                    .limit(capped)
                    .toList();
            traceTrainingProbe(true, samples.isEmpty() ? "no_match" : "ok", samples.size());
            return samples;
        } catch (IOException | RuntimeException e) {
            TraceStore.put("training.rag.probe.error", SafeRedactor.traceLabelOrFallback(
                    e.getClass().getSimpleName(), "unknown"));
            TraceStore.put("training.rag.probe.errorHash", SafeRedactor.hashValue(e.getMessage()));
            TraceStore.put("training.rag.probe.errorLength", e.getMessage() == null ? 0 : e.getMessage().length());
            traceTrainingProbe(false, "read_failed", 0);
            return List.of();
        }
    }

    private void trainInternal(Long jobId) {
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
            log.error("Training failed. errorHash={} errorLength={}", errorHash(e), errorLength(e));
            job.setStatus("FAILED");
            job.setMessage("Training failed. " + errorSummary(e));
            job.setFinishedAt(LocalDateTime.now());
            jobRepo.save(job);
        }
    }

    private static String errorSummary(Throwable error) {
        return "errorHash=" + errorHash(error) + " errorLength=" + errorLength(error);
    }

    private static String errorHash(Throwable error) {
        return SafeRedactor.hashValue(error == null ? null : String.valueOf(error));
    }

    private static int errorLength(Throwable error) {
        return error == null ? 0 : String.valueOf(error).length();
    }

    private UawAutolearnProperties effectiveAutolearnProperties() {
        return autolearnProperties == null ? new UawAutolearnProperties() : autolearnProperties;
    }

    private Path trainingRagDatasetPath() {
        UawAutolearnProperties.Dataset dataset = effectiveAutolearnProperties().getDataset();
        String path = dataset == null ? "data/train_rag.jsonl" : dataset.getPath();
        return Path.of(path == null || path.isBlank() ? "data/train_rag.jsonl" : path).normalize();
    }

    private static String sampleFromTrainingRagLine(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        try {
            JsonNode node = TRAINING_RAG_JSON.readTree(line);
            if (node.has("finalGate") && !node.path("finalGate").asBoolean(false)) {
                return "";
            }
            JsonNode validation = node.path("validation");
            if (validation.has("accepted") && !validation.path("accepted").asBoolean(false)) {
                return "";
            }
            String question = safeSampleText(node.path("question").asText(""));
            String answer = safeSampleText(node.path("answer").asText(""));
            if (answer.isBlank()) {
                return "";
            }
            return "Training RAG sample | question: " + question + " | answer: " + answer;
        } catch (IOException | RuntimeException ignored) {
            TraceStore.put("training.rag.probe.lineParseSkipped", true);
            TraceStore.put("training.rag.probe.lineParseSkipped.reason", "invalid_json");
            TraceStore.put("training.rag.probe.lineParseSkipped.errorType",
                    SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown"));
            TraceStore.put("training.rag.probe.lineParseSkipped.lineHash", SafeRedactor.hashValue(line));
            TraceStore.put("training.rag.probe.lineParseSkipped.lineLength", line.length());
            return "";
        }
    }

    private static boolean matchesTrainingQuery(String query, String sample) {
        String source = sample == null ? "" : sample.toLowerCase(Locale.ROOT);
        for (String term : (query == null ? "" : query.toLowerCase(Locale.ROOT)).split("[^a-z0-9가-힣]+")) {
            if (term.length() >= 3 && source.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String safeSampleText(String value) {
        String safe = SafeRedactor.safeMessage(value == null ? "" : value, 360);
        return safe == null ? "" : safe.strip();
    }

    private static String fileNameOf(Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }

    private static void traceTrainingProbe(boolean available, String reason, int returnedCount) {
        TraceStore.put("training.rag.probe.available", available);
        TraceStore.put("training.rag.probe.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        TraceStore.put("training.rag.probe.returnedCount", Math.max(0, returnedCount));
    }

    private static int countPresent(List<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static UawDatasetWriter.TrainingMetadata trainingMetadata(int evidenceCount,
                                                                      double qualityScore,
                                                                      boolean finalGate) {
        double score = clamp01(qualityScore);
        List<String> rejectReasons = new ArrayList<>();
        if (evidenceCount < 1) {
            rejectReasons.add("missing_citation");
        }
        if (score < 0.70d) {
            rejectReasons.add("low_quality_score");
        }
        LearningSampleValidationMetadata validation = new LearningSampleValidationMetadata(
                "training_rag",
                List.of("BQ", "ER", "RC"),
                1.0d,
                0.74d,
                0.20d,
                0.78d,
                new LearningSampleValidationMetadata.Requery(false, false),
                0.0d,
                0.0d,
                score,
                rejectReasons,
                List.of("promptbuilder_boundary", "citation_count", "quality_score"),
                LearningSampleValidationMetadata.Thresholds.defaults(),
                new LearningSampleValidationMetadata.Runtime(evidenceCount, evidenceCount, 1.0d, 1.0d, 0.0d),
                LearningSampleValidationMetadata.Anomalies.none(),
                new LearningSampleValidationMetadata.Feedback(score, "SHADOW_REVIEW"));
        return new UawDatasetWriter.TrainingMetadata(
                "training_rag",
                "internal",
                "",
                evidenceCount,
                finalGate,
                1.0d,
                validation);
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
