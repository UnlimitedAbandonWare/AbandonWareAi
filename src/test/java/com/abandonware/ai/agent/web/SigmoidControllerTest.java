package com.abandonware.ai.agent.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SigmoidControllerTest {

    @Test
    void demoUsesDefaultRequestWhenBodyIsMissing() {
        SigmoidController controller = new SigmoidController();

        Map<String, Object> response = controller.demo(null);

        assertThat(response).containsKeys("kBest", "x0Best", "combined", "ensemble");
        assertThat(response.get("combined")).isInstanceOf(Double.class);
        assertThat((Double) response.get("combined")).isFinite();
    }
}
