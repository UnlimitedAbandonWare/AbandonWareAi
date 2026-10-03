package com.example.lms.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DesktopRouterStatusBridgeControllerTest {

    @Test
    void exposesRedactedDesktopStatusWithoutRemoteProbeOrOwnerTokenLeak() throws Exception {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.provider", "local")
                .withProperty("llm.base-url", "http://127.0.0.1:11434/v1")
                .withProperty("llm.chat-model", "gemma4:26b")
                .withProperty("llm.api-key", "ollama")
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llm.provider-guard.require-auth-for-remote", "true")
                .withProperty("llmrouter.models.macmini.enabled", "false")
                .withProperty("llmrouter.models.macmini.name", "llmrouter.auto")
                .withProperty("llmrouter.models.macmini.base-url", "")
                .withProperty("llmrouter.models.macmini.node-role", "macmini-router-only-node")
                .withProperty("llmrouter.models.macmini.device", "m4-16gb")
                .withProperty("llmrouter.models.macmini.workload", "optional-subserver-route")
                .withProperty("awx.node.role", "desktop-gpu-executor")
                .withProperty("awx.node.execution-node", "desktop-rtx3090-rtx3060")
                .withProperty("awx.node.heavy-workloads-allowed", "true")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.primary-chat-base-url",
                        "http://desktop-gpu.internal:11434/v1?api_key=" + ownerToken)
                .withProperty("awx.gpu-gateway.fast-base-url", "http://desktop-gpu.internal:11435/v1")
                .withProperty("awx.gpu-gateway.embedding-base-url", "http://desktop-gpu.internal:11435/api/embed")
                .withProperty("awx.gpu-gateway.allowed-hosts", "")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "true");

        MockMvc mvc = mockMvc(env);

        String body = mvc.perform(get("/api/router/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusView").value("desktop-bridge"))
                .andExpect(jsonPath("$.nodeMode").value("desktop"))
                .andExpect(jsonPath("$.routerOnlyMode").value(false))
                .andExpect(jsonPath("$.nodeRole").value("desktop-gpu-executor"))
                .andExpect(jsonPath("$.executionNode").value("desktop-rtx3090-rtx3060"))
                .andExpect(jsonPath("$.heavyWorkloadsAllowed").value(true))
                .andExpect(jsonPath("$.probeMode").value("none"))
                .andExpect(jsonPath("$.tokenState.ownerToken").value("configured"))
                .andExpect(jsonPath("$.topology.primary3090.endpointHostPort").value("desktop-gpu.internal:11434"))
                .andExpect(jsonPath("$.topology.primary3090.redactedUrl").value(
                        "http://desktop-gpu.internal:11434/v1"))
                .andExpect(jsonPath("$.gpuHardware.status").value("disabled_by_config"))
                .andExpect(jsonPath("$['llmrouter.routes']['macmini']['enabled']").value(false))
                .andExpect(jsonPath("$['llmrouter.routes']['macmini']['disabledReason']").value("route_disabled"))
                .andExpect(jsonPath("$.routes.macmini.disabledReason").value("route_disabled"))
                .andExpect(jsonPath("$.gpuGateway.endpoints.primaryChat.status").value("host_not_allowlisted"))
                .andExpect(jsonPath("$.disabledReason").value("route_disabled"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains(ownerToken));
        assertFalse(body.contains("Authorization"));
        assertFalse(body.contains("api_key"));
    }

    @Test
    void endpointIdentityRenderingIsIndependentOfDefaultLocale() throws Exception {
        Method endpointHostPort = DesktopRouterStatusBridgeController.class
                .getDeclaredMethod("endpointHostPort", String.class);
        Method redactedUrl = DesktopRouterStatusBridgeController.class
                .getDeclaredMethod("redactedUrl", String.class);
        endpointHostPort.setAccessible(true);
        redactedUrl.setAccessible(true);

        String input = "http://DESKTOP-GPU.INTERNAL:11434/v1";
        String renderedHostPort;
        String renderedUrl;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            renderedHostPort = (String) endpointHostPort.invoke(null, input);
            renderedUrl = (String) redactedUrl.invoke(null, input);
        } finally {
            Locale.setDefault(previous);
        }

        assertAll(
                () -> assertEquals("desktop-gpu.internal:11434", renderedHostPort),
                () -> assertEquals("http://desktop-gpu.internal:11434/v1", renderedUrl));
    }

    private MockMvc mockMvc(MockEnvironment env) {
        return standaloneSetup(new DesktopRouterStatusBridgeController(env))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
