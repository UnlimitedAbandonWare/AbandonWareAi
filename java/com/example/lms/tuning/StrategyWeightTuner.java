package com.example.lms.tuning;

import com.example.lms.service.config.HyperparameterService;
import com.example.lms.strategy.StrategyPerformanceRepository;
import com.example.lms.strategy.StrategyPerformanceRepository.StatsRow;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * 성과 통계(전략별 성공/실패/보상)를 이용해
 * w_sr(성공률 가중치), w_rw(평균 보상 가중치)를
 * 중앙 차분 경사상승으로 한 스텝 미세 조정한다.
 */
@Component
@RequiredArgsConstructor
public class StrategyWeightTuner {
    private static final Logger log = LoggerFactory.getLogger(StrategyWeightTuner.class);

    private final StrategyPerformanceRepository perfRepo;
    private final HyperparameterService hp;

    @Value("${tuning.strategy.lr:0.05}")
    private double lr;

    @Value("${tuning.strategy.h:0.02}")
    private double h;

    public void tuneOnce() {
        double wSr = hp.getDouble("strategy.weight.success_rate", 0.65);
        double wRw = hp.getDouble("strategy.weight.reward",      0.30);

        // 중앙 차분: d/dw_sr
        double jPlusSr  = objective(wSr + h, wRw);
        double jMinusSr = objective(wSr - h, wRw);
        double gSr = (jPlusSr - jMinusSr) / (2 * h);

        // 중앙 차분: d/dw_rw
        double jPlusRw  = objective(wSr, wRw + h);
        double jMinusRw = objective(wSr, wRw - h);
        double gRw = (jPlusRw - jMinusRw) / (2 * h);

        // 경사 "상승": 보상 최대화
        wSr = wSr + lr * gSr;
        wRw = wRw + lr * gRw;

        // [선택] 0~1 범위 + 합=1 정규화
        wSr = clamp(wSr, 0.0, 1.0);
        wRw = clamp(wRw, 0.0, 1.0);
        double sum = wSr + wRw;
        if (sum < 1e-6) { wSr = 0.65; wRw = 0.30; sum = 0.95; }
        wSr /= sum; wRw /= sum;

        hp.set("strategy.weight.success_rate", wSr);
        hp.set("strategy.weight.reward",      wRw);
        log.info("[Tuner] strategy weights tuned → wSr={}, wRw={}", f(wSr), f(wRw));
    }

    /** 전략 전반의 기대 점수(가중 평균) - 운영 데이터 기반 근사 */
    private double objective(double wSr, double wRw) {
        List<StatsRow> rows = perfRepo.findStatsByCategory("default");
        if (rows == null || rows.isEmpty()) return 0.0;

        double totalTrials = rows.stream()
                .mapToDouble(r -> safe(r.getSuccess()) + safe(r.getFailure()))
                .sum();
        if (totalTrials < 1.0) totalTrials = 1.0;

        double acc = 0.0;
        for (StatsRow r : rows) {
            double succ   = safe(r.getSuccess());
            double fail   = safe(r.getFailure());
            double reward = Optional.ofNullable(r.getReward()).orElse(0.0);

            double trials = Math.max(1.0, succ + fail);
            double sr     = succ / trials; // 성공률
            // 기대 점수: w_sr * sr + w_rw * reward
            double score  = wSr * sr + wRw * reward;

            acc += (trials / totalTrials) * score;
        }
        return acc;
    }

    private static double safe(Long v) { return (v == null ? 0L : v); }
    private static String f(double d)  { return String.format("%.4f", d); }
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}