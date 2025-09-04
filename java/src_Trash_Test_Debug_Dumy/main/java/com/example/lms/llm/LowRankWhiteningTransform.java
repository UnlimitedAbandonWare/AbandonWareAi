package com.example.lms.llm;

import com.example.lms.service.rag.stats.LowRankWhiteningStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LowRankWhiteningTransform implements QueryTransform {
    private final LowRankWhiteningStats stats;
    @Override
    public float[] apply(float[] vec) {
        return stats.transform(vec);
    }
}
