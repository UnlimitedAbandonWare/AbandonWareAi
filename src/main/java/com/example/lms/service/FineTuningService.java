// 경로: src/main/java/com/example/lms/service/FineTuningService.java
package com.example.lms.service;

import com.example.lms.domain.TranslationSample;
import com.example.lms.dto.FineTuningOptionsDto;
import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.TrainingSampleRepository;
import com.example.lms.service.SettingsService;
import com.example.lms.util.TokenCounter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.file.File;
import com.theokanning.openai.fine_tuning.FineTuningJob;
import com.theokanning.openai.fine_tuning.FineTuningJobRequest;
import com.theokanning.openai.fine_tuning.Hyperparameters;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FineTuningService {

    private final TrainingSampleRepository trainingSampleRepo;
    private final OpenAiService            openAiService;
    private final ObjectMapper             objectMapper;
    private final SettingsService settingsService;
    private final TokenCounter             tokenCounter;
    private final CurrentModelRepository   currentModelRepo;

    @Value("${openai.api.model:gpt-3.5-turbo}")
    private String baseModelFromProperties;
    @Value("${openai.fine-tuning.max-tokens:4096}")
    private int maxTokensPerSample;

    public String startFineTuningJob(FineTuningOptionsDto opts) throws IOException {
        log.info("📊 파인튜닝 데이터 선별 시작. 기준 점수: {}", opts.qualityThreshold());

        Set<String> seenHashes = new HashSet<>();
        List<TranslationSample> filteredSamples = trainingSampleRepo.findAll().stream()
                .filter(s -> isSampleValid(s, seenHashes, opts.qualityWeighting(), opts.qualityThreshold()))
                .toList();

        if (filteredSamples.size() < 10) {
            log.warn("요구 조건을 만족하는 훈련 데이터가 10개 미만({}개)이므로 파인튜닝을 중단합니다.", filteredSamples.size());
            return null;
        }

        Collections.shuffle(filteredSamples, new Random(opts.randomSeed()));
        int validationSetSize = (int) (filteredSamples.size() * opts.validationSplitRatio());
        List<TranslationSample> validationData = filteredSamples.subList(0, validationSetSize);
        List<TranslationSample> trainingData = filteredSamples.subList(validationSetSize, filteredSamples.size());
        log.info("데이터 준비 완료. 훈련: {}개, 검증: {}개", trainingData.size(), validationData.size());

        FileWrapper trainingFile = writeAndUploadJsonl("train_", trainingData);
        FileWrapper validationFile = validationData.isEmpty() ? null : writeAndUploadJsonl("val_", validationData);

        try {
            FineTuningJobRequest request = buildRequest(trainingFile.id(), validationFile != null ? validationFile.id() : null, opts);
            FineTuningJob job = openAiService.createFineTuningJob(request);
            log.info("✅ 파인튜닝 작업 생성 성공. Job ID: {}", job.getId());
            return job.getId();
        } finally {
            trainingFile.deleteTempFile();
            if (validationFile != null) validationFile.deleteTempFile();
        }
    }

    public List<FineTuningJob> listFineTuningJobs() {
        return openAiService.listFineTuningJobs();
    }

    public Optional<FineTuningJob> checkJobStatus(String jobId) {
        try {
            FineTuningJob job = openAiService.retrieveFineTuningJob(jobId);
            if ("succeeded".equals(job.getStatus()) && job.getFineTunedModel() != null) {
                log.info("🎉 파인튜닝 성공! Job ID: {}, 생성된 모델: {}", jobId, job.getFineTunedModel());
                settingsService.save(SettingsService.KEY_FINE_TUNED_MODEL, job.getFineTunedModel());
            } else {
                log.info("작업 진행 중... Job ID: {}, 상태: {}", jobId, job.getStatus());
            }
            return Optional.of(job);
        } catch (Exception ex) {
            log.error("상태 조회 실패. Job ID: {}", jobId, ex);
            return Optional.empty();
        }
    }

    private FineTuningJobRequest buildRequest(String trainFileId, String valFileId, FineTuningOptionsDto opts) {
        String baseModel = currentModelRepo.findById(1L)
                .map(CurrentModel::getModelId)
                .orElse(baseModelFromProperties);
        log.info("파인튜닝 베이스 모델로 '{}'를 사용합니다.", baseModel);

        Hyperparameters hp = Hyperparameters.builder().nEpochs(opts.epochs()).build();

        return FineTuningJobRequest.builder()
                .trainingFile(trainFileId)
                .validationFile(valFileId)
                .model(baseModel)
                .hyperparameters(hp)
                .suffix("lms-bot-" + LocalDate.now())
                .build();
    }

    private FileWrapper writeAndUploadJsonl(String prefix, List<TranslationSample> samples) throws IOException {
        java.io.File tempFile = java.io.File.createTempFile(prefix, ".jsonl");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(tempFile.toPath()), StandardCharsets.UTF_8))) {
            for (TranslationSample sample : samples) {
                Map<String, Object> line = Map.of("messages", List.of(
                        Map.of("role", "user", "content", sample.getSourceText()),
                        Map.of("role", "assistant", "content", Optional.ofNullable(sample.getCorrected()).orElse(sample.getTranslated()))
                ));
                writer.write(objectMapper.writeValueAsString(line));
                writer.newLine();
            }
        }
        File uploadedFile = openAiService.uploadFile("fine-tune", tempFile.getPath());
        return new FileWrapper(tempFile, uploadedFile.getId());
    }

    private boolean isSampleValid(TranslationSample sample, Set<String> seenHashes, FineTuningOptionsDto.QualityWeightingDto weighting, double threshold) {
        if (sample.getSourceHash() == null || sample.getQError() == null || weighting == null) {
            return false;
        }
        return isTokenLengthValid(sample) &&
                seenHashes.add(sample.getSourceHash()) &&
                inclusionProb(sample, weighting) > threshold;
    }

    private boolean isTokenLengthValid(TranslationSample s) {
        String source = s.getSourceText();
        String target = Optional.ofNullable(s.getCorrected()).orElse(s.getTranslated());
        if (source == null || target == null) return false;

        int tokens = tokenCounter.count(source) + tokenCounter.count(target);
        return tokens <= maxTokensPerSample;
    }

    private double inclusionProb(TranslationSample s, FineTuningOptionsDto.QualityWeightingDto w) {
        if (s.getQError() == null) return 0.0;
        double q = w.qErrorWeight() * (1 - s.getQError());
        double logit = (q - 0.5) * 10; // Sigmoid-like scaling
        return 1 / (1 + Math.exp(-logit));
    }

    private record FileWrapper(java.io.File tempFile, String id) {
        void deleteTempFile() {
            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    log.warn("임시 파인튜닝 파일 삭제 실패: {}", tempFile.getAbsolutePath());
                }
            }
        }
    }
}