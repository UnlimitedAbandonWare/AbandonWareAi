// 경로: src/main/java/com/example/lms/dto/FineTuningOptionsDto.java
package com.example.lms.dto;

import java.util.Optional;



// ✨ DTO임을 명시하기 위해 이름에 Dto 접미사 추가
public record FineTuningOptionsDto(
        double qualityThreshold,
        double validationSplitRatio,
        int epochs,
        long randomSeed,
        Optional<Double> learningRate,
        Optional<Integer> batchSize,
        QualityWeightingDto qualityWeighting
) {
    public record QualityWeightingDto(double qErrorWeight, double bleuWeight) {}
}