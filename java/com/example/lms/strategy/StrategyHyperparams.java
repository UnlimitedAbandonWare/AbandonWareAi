package com.example.lms.strategy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 전략 선택 하이퍼파라미터(온도/ε)를 프로퍼티로 제어 */
@Component
public class StrategyHyperparams {

    @Value("${strategy.selector.temperature:1.0}")
    private double temperature;

    @Value("${strategy.selector.fallback.epsilon:0.10}")
    private double epsilon;

    public double temperature() { return temperature; }
    public double epsilon()     { return epsilon; }
}