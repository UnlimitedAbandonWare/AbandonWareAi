package com.example.lms.strategy;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.infra.selection.SelectionCoordinate;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.search.TraceStore;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.strategy.StrategyPerformanceRepository.StatsRow;
import com.example.lms.util.SoftmaxUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;




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
                TraceStore.put("strategy.selector.suppressed.strategyName", true);
                TraceStore.put("strategy.selector.suppressed.strategyName.errorType", "invalid_strategy");
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

            double logit = (wSr * sr) + (wRw * rw) + prior;
            if (Double.isFinite(logit)) {
                logits.put(s, logit);
            }
        }

        if (logits.isEmpty()) return base;

        // 3. 소프트맥스(Softmax)를 이용한 확률적 선택
        List<Strategy> order = new ArrayList<>(logits.keySet());
        order.sort(Comparator.comparing(Strategy::name));
        double[] arr = order.stream().mapToDouble(st -> logits.getOrDefault(st, 0.0)).toArray();

        // ✅ [개선] 온도(temperature)를 DB에서 동적으로 가져오고, 없거나 유효하지 않으면 기본값 사용
        double temp = hp.getPositiveDouble("strategy.temperature", hyper.temperature());
        double[] probs = Double.isFinite(temp) && temp > 0.0d
                ? SoftmaxUtil.softmax(arr, temp)
                : new double[arr.length];

        List<Strategy> probabilityOrder = new ArrayList<>();
        List<Double> probabilityValues = new ArrayList<>();
        for (int i = 0; i < Math.min(order.size(), probs.length); i++) {
            if (Double.isFinite(probs[i]) && probs[i] >= 0.0d) {
                probabilityOrder.add(order.get(i));
                probabilityValues.add(probs[i]);
            }
        }

        GuardContext context = GuardContextHolder.getOrDefault();
        SelectionEntropy entropy = context.selectionEntropy();
        SelectionDecisionLedger ledger = context.selectionDecisionLedger();
        SelectionCoordinate softmaxCoordinate = new SelectionCoordinate(
                "strategy.softmax", "strategy:dynamic", 0L, 0L);

        double probabilityMass = probabilityValues.stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        if (probabilityOrder.isEmpty()
                || !Double.isFinite(probabilityMass)
                || probabilityMass <= 0.0d) {
            List<String> keys = stableKeys(order);
            ledger.record(SelectionDecisionLedger.Lane.STRATEGY,
                    softmaxCoordinate, keys, 0, "zero_weight_first_stable", false,
                    order.size() > 1);
            return order.get(0);
        }

        double[] validProbabilities = probabilityValues.stream()
                .mapToDouble(Double::doubleValue)
                .toArray();
        List<String> keys = stableKeys(probabilityOrder);

        // 룰렛 휠 샘플링으로 최종 전략 선택
        int pickedIndex = rouletteIndex(
                validProbabilities, entropy.unitInterval(softmaxCoordinate));
        ledger.record(SelectionDecisionLedger.Lane.STRATEGY,
                softmaxCoordinate, keys, pickedIndex, "", true, false);
        Strategy picked = probabilityOrder.get(pickedIndex);

        // 4. ε-탐험(Epsilon-Greedy): 낮은 확률로 무작위 탐험을 수행하여 최적의 해를 놓치지 않도록 함
        double eps = hp.getDoubleInRange01("strategy.epsilon", hyper.epsilon());
        if (eps <= 0.0d) {
            return picked;
        }

        SelectionCoordinate branchCoordinate = new SelectionCoordinate(
                "strategy.epsilon-branch", "strategy:dynamic", 0L, 0L);
        boolean explore = entropy.unitInterval(branchCoordinate) < eps;
        ledger.record(SelectionDecisionLedger.Lane.STRATEGY,
                branchCoordinate, List.of("exploit", "explore"), explore ? 1 : 0,
                "", true, false);
        if (!explore) {
            return picked;
        }

        List<Strategy> all = Arrays.stream(Strategy.values())
                .sorted(Comparator.comparing(Strategy::name))
                .toList();
        SelectionCoordinate indexCoordinate = new SelectionCoordinate(
                "strategy.epsilon-index", "strategy:dynamic", 0L, 0L);
        int index = entropy.boundedIndex(indexCoordinate, all.size());
        ledger.record(SelectionDecisionLedger.Lane.STRATEGY,
                indexCoordinate, stableKeys(all), index, "", true, false);
        return all.get(index);
    }

    private static int rouletteIndex(double[] probabilities, double unit) {
        double mass = Arrays.stream(probabilities).sum();
        double target = unit * mass;
        double cumulative = 0.0d;
        for (int i = 0; i < probabilities.length; i++) {
            cumulative += probabilities[i];
            if (target < cumulative) {
                return i;
            }
        }
        return probabilities.length - 1;
    }

    private static List<String> stableKeys(List<Strategy> strategies) {
        return strategies.stream()
                .map(value -> value.name().toLowerCase(Locale.ROOT))
                .toList();
    }
}
