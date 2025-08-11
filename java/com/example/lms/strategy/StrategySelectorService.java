package com.example.lms.strategy;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.rag.QueryComplexityGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.example.lms.strategy.StrategyPerformanceRepository.StatsRow;
import com.example.lms.util.SoftmaxUtil;

@Service
@RequiredArgsConstructor
public class StrategySelectorService {

    public enum Strategy { WEB_FIRST, VECTOR_FIRST, DEEP_DIVE_SELF_ASK, WEB_VECTOR_FUSION }

    private final QueryComplexityGate gate;
    private final StrategyPerformanceRepository perfRepo;
    private final StrategyHyperparams hyper; // temperature() 등 사용

    /** 질문 특성과 과거 성과를 반영해 전략 선택 */
    public Strategy selectForQuestion(String question, ChatRequestDto req) {
        final String q = (question == null ? "" : question);
        var level = gate.assess(q);
        final Strategy base = switch (level) {
            case SIMPLE    -> Strategy.WEB_FIRST;
            case AMBIGUOUS -> Strategy.WEB_VECTOR_FUSION;
            case COMPLEX   -> Strategy.DEEP_DIVE_SELF_ASK;
        };

        List<StatsRow> rows = perfRepo.findStatsByCategory("default");
        if (rows == null || rows.isEmpty()) return base;

        Map<Strategy, Double> logits = new EnumMap<>(Strategy.class);
        for (StatsRow r : rows) {
            Strategy s;
            try { s = Strategy.valueOf(r.getStrategyName()); }
            catch (Exception ignore) { continue; }

            long suc  = Math.max(0L, java.util.Optional.ofNullable(r.getSuccess()).orElse(0L));
            long fail = Math.max(0L, java.util.Optional.ofNullable(r.getFailure()).orElse(0L));
            double trials = Math.max(1.0, suc + fail);
            double sr = suc / trials; // 성공률
            double rw = java.util.Optional.ofNullable(r.getReward()).orElse(0.0); // 평균 보상(0~1)
            double prior = (s == base ? 0.10 : 0.0); // 난이도 기반 약 prior
            logits.put(s, 0.65 * sr + 0.30 * rw + prior);
        }
        if (logits.isEmpty()) return base;

        List<Strategy> order = new ArrayList<>(logits.keySet());
        double[] arr   = order.stream().mapToDouble(s -> logits.getOrDefault(s, 0.0)).toArray();
        double[] probs = SoftmaxUtil.softmax(arr, hyper.temperature()); // subtract-max 안정화 포함

        // 룰렛 샘플링
        double r = ThreadLocalRandom.current().nextDouble(), cum = 0.0;
        for (int i = 0; i < probs.length; i++) {
            cum += probs[i];
            if (r <= cum) return order.get(i);
        }
        return order.get(order.size() - 1);
    }

    /** (옵션) 성과 통계 + 난이도 반영 점수 계산 */
    private Map<Strategy, Double> calculateStrategyScores(QueryComplexityGate.Level level) {
        Map<Strategy, Double> score = new LinkedHashMap<>();
        for (Strategy s : Strategy.values()) score.put(s, 0.5);

        List<StatsRow> rows = perfRepo.findStatsByCategory("default");
        if (rows != null) {
            for (var r : rows) {
                Strategy s;
                try { s = Strategy.valueOf(r.getStrategyName()); }
                catch (Exception ignore) { continue; }

                long succ = Math.max(0L, java.util.Optional.ofNullable(r.getSuccess()).orElse(0L));
                long fail = Math.max(0L, java.util.Optional.ofNullable(r.getFailure()).orElse(0L));
                double trials = Math.max(1.0, succ + fail);
                double successRate = succ / trials;
                double avgReward   = java.util.Optional.ofNullable(r.getReward()).orElse(0.0);

                score.put(s, 0.6 * successRate + 0.4 * avgReward);
            }
        }
        switch (level) {
            case SIMPLE    -> score.merge(Strategy.WEB_FIRST,          0.05, Double::sum);
            case AMBIGUOUS -> score.merge(Strategy.WEB_VECTOR_FUSION,  0.05, Double::sum);
            case COMPLEX   -> score.merge(Strategy.DEEP_DIVE_SELF_ASK, 0.05, Double::sum);
        }
        return score;
    }
}
