package com.example.lms.strategy;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.rag.QueryComplexityGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StrategySelectorService {

    public enum Strategy { WEB_FIRST, VECTOR_FIRST, DEEP_DIVE_SELF_ASK, WEB_VECTOR_FUSION }

    private final QueryComplexityGate gate;
    private final StrategyPerformanceRepository perfRepo;

    /** 질문 특성과 과거 성과를 반영해 전략 선택 */
    public Strategy selectForQuestion(String question, ChatRequestDto req) {
        var level = gate.assess(question == null ? "" : question);
        // 1) 기본은 난이도 기반 히ューリス틱
        Strategy base = switch (level) {
            case SIMPLE    -> Strategy.WEB_FIRST;
            case AMBIGUOUS -> Strategy.WEB_VECTOR_FUSION;
            case COMPLEX   -> Strategy.DEEP_DIVE_SELF_ASK;
        };

        // 2) 최근 성과 우위 전략이 있으면 교체(간단 Epsilon‑Greedy)
        Strategy best = perfRepo.findBestStrategyFor("default")
                .map(StrategyPerformanceRepository.BestRow::strategyName)
                .map(Strategy::valueOf)
                .orElse(base);

        double eps = 0.1; // 10%는 탐험
        if (Math.random() < eps) return Strategy.VECTOR_FIRST; // 가벼운 탐험
        return best;
    }
}