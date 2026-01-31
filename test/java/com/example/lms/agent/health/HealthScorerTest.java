package com.example.lms.agent.health;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

public class HealthScorerTest {
    @Test
    void score_increases_with_signals(){
        HealthScorer s = new HealthScorer();
        HealthWeights w = HealthWeights.defaults();
        double low = s.score(new HealthSignals(-3,-3,-3,-3,-3), w);
        double high = s.score(new HealthSignals(3,3,3,3,3), w);
        assertTrue(high > low, "High signals should produce higher score");
    }
}