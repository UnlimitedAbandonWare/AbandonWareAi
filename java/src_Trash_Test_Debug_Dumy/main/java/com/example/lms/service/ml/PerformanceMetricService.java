// 경로: com/example/lms/service/ml/PerformanceMetricService.java
package com.example.lms.service.ml;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PerformanceMetricService {
    // 시스템 전체 보상의 지수이동평균(EWMA)을 저장
    private final AtomicReference<Double> rewardEwma = new AtomicReference<>(0.7);

    /**
     * 번역 경로에 따라 보상을 받아 EWMA를 업데이트
     * @param reward (MEMORY 사용시 1.0, GT 사용시 0.7 등)
     */
    public void trackReward(double reward) {
        rewardEwma.updateAndGet(current -> current * 0.99 + reward * 0.01);
    }

    public double getRewardEwma() {
        return rewardEwma.get();
    }
}