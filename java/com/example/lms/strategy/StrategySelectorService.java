package com.example.lms.strategy;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.strategy.StrategyPerformanceRepository.StatsRow;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.util.SoftmaxUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class StrategySelectorService {

    public enum Strategy { WEB_FIRST, VECTOR_FIRST, DEEP_DIVE_SELF_ASK, WEB_VECTOR_FUSION }

    private final QueryComplexityGate gate;
    private final StrategyPerformanceRepository perfRepo;
    private final StrategyHyperparams hyper;

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
            try {
                s = Strategy.valueOf(r.getStrategyName());
            } catch (Exception ignore) {
                continue;
            }

            long succ  = Math.max(0L, Optional.ofNullable(r.getSuccess()).orElse(0L));
            long fail  = Math.max(0L, Optional.ofNullable(r.getFailure()).orElse(0L));
            double trials = Math.max(1.0, succ + fail);
            double sr = succ / trials;                               // 성공률
            double rw = Optional.ofNullable(r.getReward()).orElse(0.0); // 평균 보상(0~1)
            double prior = (s == base ? 0.10 : 0.0);                  // 난이도 기반 약한 prior

            logits.put(s, 0.65 * sr + 0.30 * rw + prior);
        }

        if (logits.isEmpty()) return base;

        // 소프트맥스 확률 분포
        List<Strategy> order = new ArrayList<>(logits.keySet());
        double[] arr   = order.stream().mapToDouble(st -> logits.getOrDefault(st, 0.0)).toArray();
        double[] probs = SoftmaxUtil.softmax(arr, hyper.temperature());

        // 룰렛 샘플링
        double r = ThreadLocalRandom.current().nextDouble();
        double cum = 0.0;
        for (int i = 0; i < probs.length; i++) {
            cum += probs[i];
            if (r <= cum) return order.get(i);
        }

        // 안전망 + ε-탐험
        Strategy picked = order.get(order.size() - 1);
        if (ThreadLocalRandom.current().nextDouble() < hyper.epsilon()) {
            return Strategy.VECTOR_FIRST;
        }
        return picked;
    }
}
