package com.example.lms.strategy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;



/** 소프트맥스 온도/탐험비율 파라미터 */
@Component
public class StrategyHyperparams {

    @Value("${strategy.selector.temperature:1.0}")
    private double temperature;

    @Value("${strategy.selector.epsilon:0.05}")
    private double epsilon;

    public double temperature() { return temperature; }
    public double epsilon() { return epsilon; }
}