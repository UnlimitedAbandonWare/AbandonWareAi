package com.example.lms.api.internal;

import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.AutoLearnCycleResult;
import com.example.lms.uaw.autolearn.AutoLearnRunStateStore;
import com.example.lms.uaw.autolearn.OpenCodeFreeQuotaGuard;
import com.example.lms.uaw.autolearn.PreemptionToken;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import com.example.lms.uaw.autolearn.UawAutolearnService;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class UawAutolearnOpsControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void statusReturnsRedactedDatasetDiagnostics() throws Exception {
        MockMvc mvc = standaloneSetup(controller(service())).build();

        String body = mvc.perform(get("/api/internal/autolearn/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.opsEnabled").value(true))
                .andExpect(jsonPath("$.datasetFile").doesNotExist())
                .andExpect(jsonPath("$.datasetFileHash").value(SafeRedactor.hashValue("train_rag_curated.jsonl")))
                .andExpect(jsonPath("$.datasetFileLength").value("train_rag_curated.jsonl".length()))
                .andExpect(jsonPath("$.externalQuota.endpointHost").value("opencode.ai"))
                .andExpect(jsonPath("$.externalQuota.model").value("deepseek-v4-flash-free"))
                .andExpect(jsonPath("$.externalQuota.routeEnabled").value(true))
                .andExpect(jsonPath("$.externalQuota.hasKey").value(true))
                .andExpect(jsonPath("$.externalQuota.nextReservationTokens").value(512))
                .andExpect(jsonPath("$.externalQuota.allowed").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("C:\\private\\train_rag_curated.jsonl"));
        assertFalse(body.contains("opencode-secret-must-not-leak"));
    }

    @Test
    void runOnceInvokesExistingServiceAndReturnsRedactedSession() throws Exception {
        UawAutolearnService service = service();
        when(service.runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong()))
                .thenReturn(new AutoLearnCycleResult(3, 2, false, "C:\\private\\train_rag_curated.jsonl",
                        0.1d, 0.0d, true, "none", "ALLOW_RETRAIN"));
        MockMvc mvc = standaloneSetup(controller(service)).build();

        String body = mvc.perform(post("/api/internal/autolearn/run-once"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(3))
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath("$.sessionHash").exists())
                .andExpect(jsonPath("$.datasetFile").doesNotExist())
                .andExpect(jsonPath("$.datasetFileHash").value(SafeRedactor.hashValue("train_rag_curated.jsonl")))
                .andExpect(jsonPath("$.datasetFileLength").value("train_rag_curated.jsonl".length()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("uaw-manual-"));
        assertFalse(body.contains("C:\\private\\train_rag_curated.jsonl"));
    }

    @Test
    void numericPropertyFallbackEmitsNamedBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/internal/UawAutolearnOpsController.java"));

        assertTrue(source.contains("traceSuppressed(\"uawOps.intProp\", ignored);"));
        assertTrue(source.contains("errorType(failure)"));
        assertTrue(source.contains("return \"invalid_number\";"));
        assertFalse(source.contains("stage,\n                    failure == null ? \"unknown\" : failure.getClass().getSimpleName()"));
    }

    private UawAutolearnOpsController controller(UawAutolearnService service) {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.setMaxCycleSeconds(1);
        props.getBudget().setStatePath(tempDir.resolve("autolearn_state.json").toString());
        props.getExternalQuota().setEnabled(true);
        MockEnvironment env = new MockEnvironment()
                .withProperty("uaw.autolearn.enabled", "true")
                .withProperty("uaw.autolearn.ops.enabled", "true")
                .withProperty("uaw.autolearn.dataset.path", "C:\\private\\train_rag_curated.jsonl")
                .withProperty("uaw.autolearn.strict.model", "llmrouter.external")
                .withProperty("llmrouter.models.external.enabled", "true")
                .withProperty("llmrouter.models.external.name", "deepseek-v4-flash-free")
                .withProperty("llmrouter.models.external.base-url", "https://opencode.ai/zen/v1")
                .withProperty("OPENCODE_API_KEY", "opencode-secret-must-not-leak");
        OpenCodeFreeQuotaGuard guard = new OpenCodeFreeQuotaGuard(props, new AutoLearnRunStateStore(), env);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("externalQuotaGuard", guard);
        return new UawAutolearnOpsController(env, props, service, new UawAutolearnQualityTracker(props, null, null),
                factory.getBeanProvider(OpenCodeFreeQuotaGuard.class));
    }

    private static UawAutolearnService service() {
        return mock(UawAutolearnService.class);
    }
}
