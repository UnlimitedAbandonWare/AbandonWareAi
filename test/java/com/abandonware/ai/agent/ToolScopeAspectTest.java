package com.abandonware.ai.agent;

import com.abandonware.ai.agent.consent.ConsentCardRenderer;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

public class ToolScopeAspectTest {
    @Test
    void consentCardRendererLoads() {
        ConsentCardRenderer r = new ConsentCardRenderer();
        String json = r.renderBasic("templates/kakao_consent_card.basic.json", "s", "r",
                java.util.List.of("kakao.push"), 3600);
        assertTrue(json.contains("basicCard"));
    }
}