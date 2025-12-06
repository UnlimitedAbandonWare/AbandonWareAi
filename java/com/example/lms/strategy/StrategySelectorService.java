package com.example.lms.strategy;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.strategy.StrategyPerformanceRepository.StatsRow;
import com.example.lms.util.SoftmaxUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;




/**
 * 사용자 질문의 특성과 각 전략의 과거 성과를 종합하여
 * 최적의 RAG 전략을 동적으로 선택하는 서비스입니다.
 */
@Service
@RequiredArgsConstructor
public class StrategySelectorService {

    public enum Strategy {
        WEB_FIRST,
        VECTOR_FIRST,
        DEEP_DIVE_SELF_ASK,
        WEB_VECTOR_FUSION
    }

    private final QueryComplexityGate gate;
    private final StrategyPerformanceRepository perfRepo;
    private final StrategyHyperparams hyper;
    private final HyperparameterService hp; // 동적 제어를 위한 하이퍼파라미터 서비스

    /**
     * 질문 특성과 과거 성과를 반영해 최적의 전략을 선택합니다.
     *
     * @param question 사용자 원본 질문
     * @param req      채팅 요청 DTO
     * @return 선택된 RAG 전략
     */
    public Strategy selectForQuestion(String question, ChatRequestDto req) {
        final String q = (question == null ? "" : question);
        var level = gate.assess(q);

        // 1. 질문 복잡도에 따라 기본(base) 전략 결정
        final Strategy base = switch (level) {
            case SIMPLE -> Strategy.WEB_FIRST;
            case AMBIGUOUS -> Strategy.WEB_VECTOR_FUSION;
            case COMPLEX -> Strategy.DEEP_DIVE_SELF_ASK;
        };

        List<StatsRow> rows = perfRepo.findStatsByCategory("default");
        if (rows == null || rows.isEmpty()) return base;

        // 2. 각 전략의 점수(logit) 계산
        Map<Strategy, Double> logits = new EnumMap<>(Strategy.class);
        for (StatsRow r : rows) {
            Strategy s;
            try {
                s = Strategy.valueOf(r.getStrategyName());
            } catch (Exception ignore) {
                continue;
            }

            long succ = Math.max(0L, Optional.ofNullable(r.getSuccess()).orElse(0L));
            long fail = Math.max(0L, Optional.ofNullable(r.getFailure()).orElse(0L));
            double trials = Math.max(1.0, succ + fail);
            double sr = succ / trials; // 성공률 (0.0 ~ 1.0)
            double rw = Optional.ofNullable(r.getReward()).orElse(0.0); // 평균 보상 (0.0 ~ 1.0)

            // 점수 계산 시 동적 가중치와 우선순위(prior) 값을 DB에서 가져와 적용
            double prior = (s == base) ? hp.getDouble("strategy.prior.base", 0.10) : 0.0;
            double wSr = hp.getDouble("strategy.weight.success_rate", 0.65);
            double wRw = hp.getDouble("strategy.weight.reward", 0.30);

            logits.put(s, (wSr * sr) + (wRw * rw) + prior);
        }

        if (logits.isEmpty()) return base;

        // 3. 소프트맥스(Softmax)를 이용한 확률적 선택
        List<Strategy> order = new ArrayList<>(logits.keySet());
        double[] arr = order.stream().mapToDouble(st -> logits.getOrDefault(st, 0.0)).toArray();

        // ✅ [개선] 온도(temperature)를 DB에서 동적으로 가져오고, 없거나 유효하지 않으면 기본값 사용
        double temp = hp.getPositiveDouble("strategy.temperature", hyper.temperature());
        double[] probs = SoftmaxUtil.softmax(arr, temp);

        // 룰렛 휠 샘플링으로 최종 전략 선택
        double r = ThreadLocalRandom.current().nextDouble();
        double cum = 0.0;
        Strategy picked = order.get(order.size() - 1); // 안전망: 루프가 끝나도 마지막 전략 선택
        for (int i = 0; i < probs.length; i++) {
            cum += probs[i];
            if (r <= cum) {
                picked = order.get(i);
                break;
            }
        }

        // 4. ε-탐험(Epsilon-Greedy): 낮은 확률로 무작위 탐험을 수행하여 최적의 해를 놓치지 않도록 함
        double eps = hp.getDoubleInRange01("strategy.epsilon", hyper.epsilon());
        if (ThreadLocalRandom.current().nextDouble() < eps) {
            Strategy[] all = Strategy.values();
            return all[ThreadLocalRandom.current().nextInt(all.length)];
        }

        return picked;
    }
}