// 경로: com/example/lms/service/QualityMetricService.java
package com.example.lms.service;

import com.example.lms.util.TextSimilarityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QualityMetricService {

    private final TextSimilarityUtil textSimilarityUtil;

    // source와 translated 텍스트의 유사도를 계산하여 품질 점수로 반환 (0.0 ~ 1.0)
    public double calculateScore(String source, String translated) {
        if (source == null || translated == null) {
            return 0.0;
        }
        // 실제로는 BLEU, ROUGE 등의 지표를 사용해야 합니다.
        // 여기서는 TextSimilarityUtil을 재사용하여 간단히 구현합니다.
        return textSimilarityUtil.calculateSimilarity(source, translated);
    }
}