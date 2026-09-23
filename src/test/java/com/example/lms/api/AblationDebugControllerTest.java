package com.example.lms.api;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AblationDebugControllerTest {

    @Test
    void ablationRequiresConfiguredTokenMatch() {
        Environment env = new MockEnvironment()
                .withProperty("uaw.ablation.penalty.default", "0.1");
        AblationDebugController controller = new AblationDebugController(env, true, "admin-token");

        assertEquals(401, controller.ablation("wrong-token").getStatusCode().value());
        assertEquals(200, controller.ablation("admin-token").getStatusCode().value());
    }

    @Test
    void ablationStaysDisabledWhenEnabledWithoutToken() {
        Environment env = new MockEnvironment();
        AblationDebugController controller = new AblationDebugController(env, true, "");

        assertEquals(404, controller.ablation("anything").getStatusCode().value());
    }
}
