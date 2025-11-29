package com.example.lms.tuning;

import com.example.lms.service.config.HyperparameterService;
import com.example.lms.strategy.StrategyPerformanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


import com.example.lms.tuning.StrategyWeightTuner; // NEW
/** 매일 자정에 최근 성과를 바탕으로 하이퍼파라미터를 미세 조정 */
@Component
@RequiredArgsConstructor
public class DynamicHyperparameterTuner {
    private static final Logger log = LoggerFactory.getLogger(DynamicHyperparameterTuner.class);

    private final HyperparameterService hp;
    private final StrategyPerformanceRepository perfRepo;
    private final StrategyWeightTuner strategyWeightTuner; // NEW

    /** 매일 00:05 */
    @Scheduled(cron = "0 5 0 * * *")
    public void retune() {
        try {
            // 예시: 최근 성공률이 낮으면 탐험 비율↑, 임계값↓
            double defaultExplore = hp.getDouble("bandit.explore.rate", 0.10);
            double baseThreshold  = hp.getDouble("bandit.threshold.base", 0.85);

            var best = perfRepo.findBestStrategyFor("default");
            boolean poor = best.isEmpty(); // 단순 예: 데이터 부족→탐험↑
            double newExplore = clamp(defaultExplore + (poor ? 0.05 : -0.01), 0.05, 0.30);
            double newTh      = clamp(baseThreshold + (poor ? -0.02 : 0.0), 0.75, 0.90);

            hp.set("bandit.explore.rate", newExplore);
            hp.set("bandit.threshold.base", newTh);

            log.info("[Tuner] explore={} threshold={}", newExplore, newTh);

            // NEW: 전략 가중치(성공률/보상) 중앙차분 경사상승 1스텝
            strategyWeightTuner.tuneOnce(); // 내부에서 objective 중앙차분 및 저장 수행
        } catch (Exception e) {
            log.warn("[Tuner] retune failed: {}", e.toString());
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}