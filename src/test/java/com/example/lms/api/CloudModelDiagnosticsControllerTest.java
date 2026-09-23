package com.example.lms.api;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.llm.gateway.LlmRouteScorer;
import com.example.lms.llm.gateway.CloudModelRouteClassifier;
import com.example.lms.llm.spec.CloudModelCatalogLoader;
import com.example.lms.llm.spec.CloudModelMetadataProbe;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CloudModelDiagnosticsControllerTest {

    @Test
    void cloudModelDiagnosticsExposeReadOnlyTableWithoutRawCredentialLeak() throws Exception {
        String fixtureCredential = "real-looking-value-123";
        MockMvc mvc = standaloneSetup(new CloudModelDiagnosticsController(classifier(fixtureCredential)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        String body = mvc.perform(get("/api/diagnostics/llm/cloud-models").param("stage", "chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("awx.llm.cloud_models.v1"))
                .andExpect(jsonPath("$.stage").value("chat"))
                .andExpect(jsonPath("$.count").value(7))
                .andExpect(jsonPath("$.eligibleCount").value(0))
                .andExpect(jsonPath("$.rows[2].provider").value("groq"))
                .andExpect(jsonPath("$.rows[2].modelId").value("openai/gpt-oss-120b"))
                .andExpect(jsonPath("$.rows[2].routeKey").value("api3"))
                .andExpect(jsonPath("$.rows[2].disabledReason").value("cloud_manifest_disabled"))
                .andExpect(jsonPath("$.rows[2].eligible").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains(fixtureCredential));
        assertFalse(body.contains("Authorization"));
        assertFalse(body.contains("Cookie"));
    }

    private static CloudModelRouteClassifier classifier(String groqCredential) {
        LlmGatewayProperties gatewayProps = new LlmGatewayProperties();
        gatewayProps.getSpecRegistry().setEnabled(false);
        LlmRouterProperties routerProps = new LlmRouterProperties();
        LlmRouterProperties.ModelConfig groq = new LlmRouterProperties.ModelConfig();
        groq.setEnabled(true);
        groq.setProvider("groq");
        groq.setName("openai/gpt-oss-120b");
        groq.setBaseUrl("https://api.groq.com/openai/v1");
        groq.setFallbackOnly(true);
        groq.setWeight(0.0d);
        routerProps.setModels(Map.of("api3", groq));
        return new CloudModelRouteClassifier(
                new CloudModelCatalogLoader(new CloudModelMetadataProbe()),
                routerProps,
                new HybridLlmGatewayProbeService(
                        gatewayProps,
                        new ModelRuntimeHealthTracker(),
                        new ModelSpecRegistry(new ObjectMapper(), gatewayProps),
                        new LlmRouteScorer(),
                        new MockEnvironment().withProperty("GROQ_API_KEY", groqCredential)));
    }
}
