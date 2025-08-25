  package com.example.lms.service;

import com.example.lms.domain.TranslationSample;
import com.example.lms.dto.FineTuningJobDto;
import com.example.lms.dto.FineTuningOptionsDto;
import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.TrainingSampleRepository;
import com.example.lms.util.TokenCounter;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper             objectMapper;
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

        FileWrapper trainingFile = writeJsonl("train_", trainingData);
        FileWrapper validationFile = validationData.isEmpty() ? null : writeJsonl("val_", validationData);
        try {
            log.warn("현재 빌드는 LC4J 1.0.1 순정 빌드로, 외부 OpenAI Fine-Tuning 클라이언트를 포함하지 않습니다. " +
                            "오프라인 JSONL만 생성 후 종료합니다. train={}, val={}",
                    trainingFile.tempFile().getAbsolutePath(),
                    validationFile != null ? validationFile.tempFile().getAbsolutePath() : "(none)");
            // ✅ 컴파일 우선 복구: 실제 Fine-Tuning API 호출은 별도 WebClient/HTTP 클라이언트로 교체 구현 필요.
            return null; // Controller에서 '데이터 부족' 메시지를 띄우는 흐름을 유지
        } finally {
            trainingFile.deleteTempFile();
            if (validationFile != null) validationFile.deleteTempFile();
        }
    }

    public List<FineTuningJobDto> listFineTuningJobs() {
        // LC4J-only 스텁: 빈 리스트 반환 (추후 WebClient로 대체 가능)
        return List.of();
    }

    public Optional<FineTuningJobDto> checkJobStatus(String jobId) {
        // LC4J-only 스텁: 상태 조회 미지원
        return Optional.empty();
    }

    // ❌ 외부 SDK 의존 제거로 요청 빌더 삭제

    private FileWrapper writeJsonl(String prefix, List<TranslationSample> samples) throws IOException {
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
        // 업로드 미수행: 로컬 파일 경로만 래핑
        return new FileWrapper(tempFile, tempFile.getAbsolutePath());
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