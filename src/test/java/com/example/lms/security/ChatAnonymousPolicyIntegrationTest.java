package com.example.lms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ChatAnonymousPolicyIntegrationTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = {"conversate.display.enabled=true", "demo.interview.enabled=false"})
class ChatAnonymousPolicyIntegrationTest {
    @Configuration
    @EnableWebMvc
    @Import({ChatOpenSecurityConfig.class, Probe.class})
    static class Config { }

    @RestController
    static class Probe {
        @GetMapping({"/chat", "/api/chat/sessions", "/api/chat/state", "/ws/probe", "/actuator/env"})
        String read() { return "admitted"; }
        @PostMapping("/api/chat/stream")
        String stream() { return "admitted"; }
    }

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired ChatOpenSecurityConfig security;
    MockMvc mvc;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(security, "publicDisplay", true);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
    }

    @Test void displayFlagDoesNotChangeAnonymousChatAdmission() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            ReflectionTestUtils.setField(security, "publicDisplay", enabled);
            for (String path : new String[]{"/chat", "/api/chat/sessions", "/api/chat/state"}) {
                mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string("admitted"));
            }
            mvc.perform(post("/api/chat/stream").contentType("application/json").content("{}"))
                    .andExpect(status().isOk());
        }
    }

    @Test void crossOriginChatRemainsDenied() throws Exception {
        mvc.perform(post("/api/chat/stream").header("Origin", "https://foreign.example")
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test void displayWebsocketStillRequiresAuthenticationAndReturnsJson() throws Exception {
        mvc.perform(get("/ws/probe")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("auth_required"))
                .andExpect(jsonPath("$.providerAttempted").value(false));
    }

    @Test void sensitiveActuatorIsStillDenied() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
