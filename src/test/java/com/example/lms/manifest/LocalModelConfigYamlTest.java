package com.example.lms.manifest;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelConfigYamlTest {
    private static final System.Logger LOG = System.getLogger(LocalModelConfigYamlTest.class.getName());

    @Test
    void allMainAndAppResourceYamlFilesParseWithSnakeYaml() throws IOException {
        List<Path> yamlFiles = new ArrayList<>();
        for (Path root : List.of(Path.of("main/resources"), Path.of("app/src/main/resources"))) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(LocalModelConfigYamlTest::isYaml)
                        .forEach(yamlFiles::add);
            }
        }

        List<String> failures = new ArrayList<>();
        Yaml yaml = new Yaml();
        for (Path yamlFile : yamlFiles) {
            try (InputStream in = Files.newInputStream(yamlFile)) {
                yaml.load(in);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "YAML parse test failure pathLength={0} errorType={1}",
                        yamlFile.toString().length(), e.getClass().getSimpleName());
                failures.add(yamlFile + " :: " + e.getMessage());
            }
        }

        assertTrue(failures.isEmpty(), () -> "YAML parse failures:\n" + String.join("\n", failures));
    }

    @Test
    void uawDatasetFilterRegexPatternsCompile() throws IOException {
        Map<?, ?> root = loadMap(Path.of("main/resources/application-llm.yaml"));
        Map<?, ?> datasetFilter = map(map(map(root, "uaw"), "autolearn"), "dataset-filter");
        List<?> rules = list(datasetFilter, "rules");

        List<String> failures = new ArrayList<>();
        for (Object rawRule : rules) {
            if (!(rawRule instanceof Map<?, ?> rule)) {
                continue;
            }
            Object rawName = rule.get("name");
            String ruleName = rawName == null ? "unnamed" : String.valueOf(rawName);
            Object rawPatterns = rule.get("patterns");
            if (!(rawPatterns instanceof List<?> patterns)) {
                continue;
            }
            for (int i = 0; i < patterns.size(); i++) {
                Object rawPattern = patterns.get(i);
                if (!(rawPattern instanceof String pattern) || pattern.isBlank()) {
                    continue;
                }
                try {
                    Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
                } catch (PatternSyntaxException e) {
                    LOG.log(System.Logger.Level.DEBUG,
                            "UAW dataset regex compile failure ruleLength={0} patternIndex={1} errorType={2}",
                            ruleName.length(), i, e.getClass().getSimpleName());
                    failures.add(ruleName + "[" + i + "] :: " + e.getDescription());
                }
            }
        }

        assertTrue(failures.isEmpty(),
                () -> "UAW dataset-filter regex compile failures:\n" + String.join("\n", failures));
    }

    @Test
    void modelManifestsKeepRoleAwareLocalBindingsAndAliases() throws IOException {
        assertLocalManifest(Path.of("main/resources/configs/models.manifest.yaml"));
        assertLocalManifest(Path.of("app/src/main/resources/configs/models.manifest.yaml"));

        Map<?, ?> appManifest = loadMap(Path.of("app/src/main/resources/configs/models.manifest.yaml"));
        Map<?, ?> routing = map(appManifest, "routing");
        List<?> rules = (List<?>) routing.get("rules");
        assertEquals("smtek/Qwen3.8-27B:Q3_K_XL", ((Map<?, ?>) rules.get(0)).get("use"));
        assertEquals("gemma4:26b", ((Map<?, ?>) rules.get(1)).get("use"));
        assertEquals("gemma4:26b", ((Map<?, ?>) rules.get(2)).get("use"));
    }

    @Test
    void localApplicationYamlKeepsQwenAndGemmaOnSeparateRoles() throws IOException {
        assertLocalApplicationRoles(Path.of("main/resources/application-local.yml"));
        assertLocalApplicationRoles(Path.of("app/src/main/resources/application-local.yml"));
    }

    @Test
    void llmOverlayKeepsFastChatOnPrimaryUnlessFastEndpointIsExplicit() throws IOException {
        Map<?, ?> root = loadMap(Path.of("main/resources/application-llm.yaml"));
        Map<?, ?> llm = map(root, "llm");
        Map<?, ?> embedding = map(root, "embedding");

        assertEquals("${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11435/v1}}", llm.get("base-url"));
        assertEquals("${LLM_API_KEY:ollama}", llm.get("api-key"));
        assertEquals("${LLM_CHAT_MODEL:gemma4:26b}", llm.get("chat-model"));
        assertEquals("${LLM_OWNER_TOKEN:}", llm.get("owner-token"));
        assertEquals("${LLM_OWNER_TOKEN_HEADER:X-Owner-Token}", llm.get("owner-token-header"));
        assertEquals("${EMBED_BASE_URL:${EMBED_3060_BASE_URL:http://127.0.0.1:11435/api/embed}}", embedding.get("base-url"));
        assertEquals("${EMBED_MODEL:qwen3-embedding:4b}", embedding.get("model"));
        assertEquals("${EMBED_PROVIDER_RAW_DIMENSIONS:2560}", embedding.get("provider-raw-dimensions"));
        assertEquals("${EMBED_DIMENSIONS:1536}", embedding.get("dimensions"));
        assertEquals("${EMBED_NORMALIZATION_MODE:SLICE_TO_CONFIGURED_DIM}", embedding.get("normalization-mode"));

        Map<?, ?> fast = map(llm, "fast");
        Map<?, ?> high = map(llm, "high");
        Map<?, ?> judge = map(llm, "judge");
        Map<?, ?> coder = map(llm, "coder");
        Map<?, ?> vision = map(llm, "vision");
        assertEquals("${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}", fast.get("base-url"));
        assertEquals("${LLM_FAST_MODEL:qwen3.5:9b}", fast.get("model"));
        assertEquals("${LLM_HIGH_BASE_URL:${LLM_3090_BASE_URL:${llm.base-url}}}", high.get("base-url"));
        assertEquals("${LLM_HIGH_MODEL:${llm.chat-model:gemma4:26b}}", high.get("model"));
        assertEquals("${LLM_JUDGE_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}", judge.get("model"));
        assertEquals("${LLM_CODER_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}", coder.get("model"));
        assertEquals("${LLM_VISION_MODEL:qwen3-vl:8b}", vision.get("model"));

        Map<?, ?> ollama = map(llm, "ollama");
        Map<?, ?> localLlm = map(root, "local-llm");
        Map<?, ?> warmup = map(localLlm, "warmup");
        assertEquals("${llm.base-url:http://127.0.0.1:11434/v1}", ollama.get("base-url"));
        assertEquals("${llm.chat-model:gemma4:26b}", ollama.get("chat-model"));
        assertEquals("${embedding.model:qwen3-embedding:4b}", ollama.get("embed-model"));
        assertEquals("${LOCAL_LLM_ENABLED:true}", localLlm.get("enabled"));
        assertEquals("${LOCAL_LLM_AUTOSTART:true}", localLlm.get("autostart"));
        assertEquals("${OLLAMA_HOST:127.0.0.1:11435}", localLlm.get("ollama-host"));
        assertEquals("${LOCAL_LLM_WARMUP_ENABLED:true}", warmup.get("enabled"));
        assertEquals("${LOCAL_LLM_WARMUP_DIMENSIONS:${embedding.dimensions:1536}}", warmup.get("dimensions"));
        assertEquals("${TAVILY_ENABLED:false}", root.get("tavily.enabled"));
        assertEquals("${SELFASK_ENABLED:false}", root.get("selfask.enabled"));
        assertEquals("${RAG_COGNITIVE_ENABLED:true}", map(map(root, "rag"), "cognitive").get("enabled"));

        Map<?, ?> router = map(root, "llmrouter");
        assertEquals(Boolean.TRUE, router.get("enabled"));
        Map<?, ?> routerAliases = map(router, "aliases");
        assertEquals("${LLM_FAST_MODEL:qwen3.5:9b}", routerAliases.get("fast"));
        assertEquals("${LLM_JUDGE_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}", routerAliases.get("judge"));
        assertEquals("${LLM_CODER_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}", routerAliases.get("coder"));
        assertEquals("${LLM_VISION_MODEL:qwen3-vl:8b}", routerAliases.get("vision"));

        Map<?, ?> routerModels = map(router, "models");
        assertEquals("${LLMROUTER_LIGHT_NAME:${LLM_FAST_MODEL:qwen3.5:9b}}", map(routerModels, "light").get("name"));
        assertEquals("${LLMROUTER_GEMMA_NAME:${llm.chat-model:gemma4:26b}}", map(routerModels, "gemma").get("name"));
        assertEquals("${LLMROUTER_JUDGE_NAME:${LLM_JUDGE_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}}", map(routerModels, "judge").get("name"));
        assertEquals("${LLMROUTER_CODER_NAME:${LLM_CODER_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}}", map(routerModels, "coder").get("name"));
        assertEquals("${LLMROUTER_VISION_NAME:${LLM_VISION_MODEL:qwen3-vl:8b}}", map(routerModels, "vision").get("name"));
        assertEquals("${LLMROUTER_MACMINI_NAME:${MACMINI_API_ROUTER_MODEL:llmrouter.auto}}", map(routerModels, "macmini").get("name"));
        assertEquals("${LLMROUTER_MACMINI_BASE_URL:${MACMINI_API_ROUTER_BASE_URL:}}", map(routerModels, "macmini").get("base-url"));
        assertEquals("macmini-router-only-node", map(routerModels, "macmini").get("node-role"));
        assertEquals("m4-16gb", map(routerModels, "macmini").get("device"));
        assertEquals("optional-subserver-route", map(routerModels, "macmini").get("workload"));
    }

    @Test
    void baseApplicationKeepsFastQwenOnPrimaryUnless3060RouteIsExplicit() throws IOException {
        Map<?, ?> root = loadMap(Path.of("main/resources/application.yml"));
        Map<?, ?> fast = map(map(root, "llm"), "fast");

        assertEquals("${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}",
                fast.get("base-url"));
        assertEquals("${LLM_FAST_MODEL:qwen3.5:9b}", fast.get("model"));
    }

    @Test
    void localLlmProfileKeepsFastChatRoutesOnPrimaryUnless3060RouteIsExplicit() throws IOException {
        Map<?, ?> root = loadMap(Path.of("main/resources/application-local-llm.yml"));
        Map<?, ?> llm = map(root, "llm");
        Map<?, ?> models = map(llm, "models");
        Map<?, ?> routes = map(map(llm, "routing"), "model-to-endpoint");

        String fastFallback = "${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}";
        String visionFallback =
                "${LLM_VISION_BASE_URL:${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}}";

        assertEquals(fastFallback, map(models, "gemma3_4b").get("endpoint"));
        assertEquals(fastFallback, map(models, "qwen25_7b").get("endpoint"));
        assertEquals(fastFallback, map(models, "qwen3_8b").get("endpoint"));
        assertEquals(visionFallback, map(models, "qwen3_vl_8b").get("endpoint"));
        assertEquals(fastFallback, routes.get("gemma3_4b"));
        assertEquals(fastFallback, routes.get("qwen25_7b"));
        assertEquals(fastFallback, routes.get("qwen3_8b"));
        assertEquals(visionFallback, routes.get("qwen3_vl_8b"));
    }

    @Test
    void llmRouterExternalDefaultsToDisabledOpenCodeDeepSeekRoute() throws IOException {
        Map<?, ?> root = loadMap(Path.of("main/resources/application-llm.yaml"));
        Map<?, ?> external = map(map(map(root, "llmrouter"), "models"), "external");

        assertEquals("${LLMROUTER_EXTERNAL_ENABLED:false}", external.get("enabled"));
        assertEquals("${LLMROUTER_EXTERNAL_NAME:deepseek-v4-flash-free}", external.get("name"));
        assertEquals("${LLMROUTER_EXTERNAL_BASE_URL:https://opencode.ai/zen/v1}", external.get("base-url"));
        assertEquals("${LLMROUTER_EXTERNAL_WEIGHT:0.0}", external.get("weight"));
    }

    @Test
    void llmRouterCloudRoutesDefaultToDisabledFallbackOnlyAndCredentialScoped() throws IOException {
        Map<?, ?> root = loadMap(Path.of("main/resources/application-llm.yaml"));
        Map<?, ?> models = map(map(root, "llmrouter"), "models");

        assertDisabledCloudRoute(models, "openai-premium", "openai", "OPENAI_API_KEY");
        assertDisabledCloudRoute(models, "openai-balanced", "openai", "OPENAI_API_KEY");
        assertDisabledCloudRoute(models, "gemini-pro", "gemini", "GEMINI_API_KEY");
        assertDisabledCloudRoute(models, "mistral-medium", "mistral", "MISTRAL_API_KEY");
        assertEquals("${LLMROUTER_MISTRAL_MEDIUM_NAME:mistral-medium-3-5}",
                map(models, "mistral-medium").get("name"));
    }

    @Test
    void uawExternalQuotaDefaultsGuardOpenCodeFreeModel() throws IOException {
        Map<?, ?> root = loadMap(Path.of("main/resources/application-llm.yaml"));
        Map<?, ?> externalQuota = map(map(map(root, "uaw"), "autolearn"), "external-quota");

        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_ENABLED:false}", externalQuota.get("enabled"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_ROUTE_MODEL:llmrouter.external}", externalQuota.get("route-model"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_PROVIDER_HOST:opencode.ai}", externalQuota.get("provider-host"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_FREE_MODEL:deepseek-v4-flash-free}", externalQuota.get("free-model"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_MAX_CALLS_PER_DAY:3}", externalQuota.get("max-calls-per-day"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_MAX_OUTPUT_TOKENS_PER_DAY:1536}", externalQuota.get("max-output-tokens-per-day"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_MAX_OUTPUT_TOKENS_PER_CALL:512}", externalQuota.get("max-output-tokens-per-call"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_MAX_CALLS_PER_CYCLE:1}", externalQuota.get("max-calls-per-cycle"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_RATE_LIMIT_COOLDOWN_SECONDS:86400}", externalQuota.get("rate-limit-cooldown-seconds"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_STRICT_FREE_MODEL_ONLY:true}", externalQuota.get("strict-free-model-only"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_RESET_ZONE:UTC}", externalQuota.get("reset-zone"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_PRIVACY_MODE:STATIC_SYNTHETIC_ONLY}", externalQuota.get("privacy-mode"));
        assertEquals("${UAW_AUTOLEARN_EXTERNAL_QUOTA_CANONICAL_TRAINING_POLICY:EXTERNAL_FREE_CURATE_ONLY}",
                externalQuota.get("canonical-training-policy"));
    }

    @Test
    void localLlmProfileUsesRoleAwareModelCatalog() throws IOException {
        Map<?, ?> root = loadMap(Path.of("main/resources/application-local-llm.yml"));
        Map<?, ?> llm = map(root, "llm");
        Map<?, ?> embedding = map(root, "embedding");

        assertEquals("${LLM_API_KEY:ollama}", llm.get("api-key"));
        assertEquals("${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11435/v1}}", llm.get("base-url"));
        assertEquals("${LLM_CHAT_MODEL:gemma4:26b}", llm.get("chat-model"));
        assertEquals("gemma4_26b", llm.get("defaultModel"));
        assertEquals("${EMBED_BASE_URL:${EMBED_3060_BASE_URL:http://127.0.0.1:11435/api/embed}}", embedding.get("base-url"));
        assertEquals("${EMBED_MODEL:qwen3-embedding:4b}", embedding.get("model"));
        assertEquals("${EMBED_PROVIDER_RAW_DIMENSIONS:2560}", embedding.get("provider-raw-dimensions"));
        assertEquals("${EMBED_DIMENSIONS:1536}", embedding.get("dimensions"));
        Map<?, ?> localLlm = map(root, "local-llm");
        Map<?, ?> warmup = map(localLlm, "warmup");
        assertEquals("${LOCAL_LLM_AUTOSTART:true}", localLlm.get("autostart"));
        assertEquals("${OLLAMA_HOST:127.0.0.1:11435}", localLlm.get("ollama-host"));
        assertEquals("${LOCAL_LLM_WARMUP_ENABLED:true}", warmup.get("enabled"));
        assertEquals("${LOCAL_LLM_WARMUP_DIMENSIONS:${embedding.dimensions:1536}}", warmup.get("dimensions"));

        Map<?, ?> models = map(llm, "models");
        assertEquals("${LLM_CHAT_MODEL:gemma4:26b}", map(models, "gemma4_26b").get("name"));
        assertEquals("${LLM_JUDGE_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}", map(models, "qwen3_30b").get("name"));
        assertEquals("${LLM_CODER_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}", map(models, "qwen3_coder_30b").get("name"));
        assertEquals("${LLM_VISION_MODEL:qwen3-vl:8b}", map(models, "qwen3_vl_8b").get("name"));
        assertEquals("${LLM_FAST_MODEL:qwen3.5:9b}", map(models, "qwen3_8b").get("name"));
        assertLocalGpuPlacement(models);
        assertEquals("rtx3060", map(models, "qwen25_7b").get("gpu"));
        assertEquals("${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11435/v1}}",
                map(models, "gemma4_26b").get("endpoint"));
        assertEquals("${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}",
                map(models, "gemma3_4b").get("endpoint"));
        assertEquals("${LLM_JUDGE_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11435/v1}}",
                map(models, "qwen3_30b").get("endpoint"));
        assertEquals("${LLM_CODER_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11435/v1}}",
                map(models, "qwen3_coder_30b").get("endpoint"));
        assertEquals("${LLM_VISION_BASE_URL:${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}}",
                map(models, "qwen3_vl_8b").get("endpoint"));
        Map<?, ?> modelToEndpoint = map(map(llm, "routing"), "model-to-endpoint");
        assertEquals("${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}",
                modelToEndpoint.get("qwen3_8b"));
    }

    @Test
    void nodeProfilesSeparateDesktopGpuExecutionFromMacMiniCuration() throws IOException {
        Map<?, ?> desktop = loadMap(Path.of("main/resources/application-desktop-gpu-node.yml"));
        Map<?, ?> desktopAwxNode = map(map(desktop, "awx"), "node");
        Map<?, ?> desktopBridge = map(map(desktop, "awx"), "desktop-status-bridge");
        Map<?, ?> desktopGpuGateway = map(map(desktop, "awx"), "gpu-gateway");
        Map<?, ?> desktopCollector = map(map(map(desktop, "awx"), "learning-ops"), "collector");
        Map<?, ?> desktopLocalLlm = map(desktop, "local-llm");
        Map<?, ?> desktopUawNode = map(map(map(desktop, "uaw"), "autolearn"), "runtime-node");
        Map<?, ?> desktopRouterModels = map(map(desktop, "llmrouter"), "models");

        assertEquals("${DESKTOP_STATUS_BRIDGE_ENABLED:true}", desktopBridge.get("enabled"));
        assertEquals("desktop-gpu-executor", desktopAwxNode.get("role"));
        assertEquals(Boolean.TRUE, desktopAwxNode.get("heavy-workloads-allowed"));
        assertEquals(Boolean.TRUE, desktopGpuGateway.get("enabled"));
        assertEquals("desktop-rtx3090-rtx3060", desktopGpuGateway.get("target-execution-node"));
        assertEquals("${LLM_3090_BASE_URL:${LLM_BASE_URL:http://127.0.0.1:11435/v1}}", desktopGpuGateway.get("primary-chat-base-url"));
        assertEquals("${LLM_3060_BASE_URL:${LLM_FAST_BASE_URL:http://127.0.0.1:11435/v1}}", desktopGpuGateway.get("fast-base-url"));
        assertEquals("${EMBED_3060_BASE_URL:${EMBED_BASE_URL:http://127.0.0.1:11435/api/embed}}", desktopGpuGateway.get("embedding-base-url"));
        assertEquals("${LLM_OWNER_TOKEN:}", desktopGpuGateway.get("owner-token"));
        assertEquals("${LLM_OWNER_TOKEN_HEADER:X-Owner-Token}", desktopGpuGateway.get("owner-token-header"));
        assertEquals("${LOCAL_LLM_AUTOSTART:true}", desktopLocalLlm.get("autostart"));
        assertEquals("${LOCAL_LLM_WARMUP_ENABLED:true}", map(desktopLocalLlm, "warmup").get("enabled"));
        assertEquals("${DESKTOP_LEARNING_OPS_COLLECTOR_ENABLED:false}", desktopCollector.get("enabled"));
        Map<?, ?> desktopAutolearn = map(map(desktop, "uaw"), "autolearn");
        assertEquals("${DESKTOP_AUTOLEARN_ENABLED:false}", desktopAutolearn.get("enabled"));
        assertEquals("${DESKTOP_AUTOLEARN_IDLE_TRIGGER_ENABLED:false}",
                map(desktopAutolearn, "idle-trigger").get("enabled"));
        assertEquals("rtx3090", map(desktopRouterModels, "gemma").get("device"));
        assertEquals("rtx3060", map(desktopRouterModels, "light").get("device"));
        assertEquals("execute-and-curate", desktopUawNode.get("learning-loop-mode"));
        assertEquals("${DESKTOP_AUTOLEARN_RETRAIN_ENABLED:false}", map(desktopAutolearn, "retrain").get("enabled"));

        Map<?, ?> macmini = loadMap(Path.of("main/resources/application-macmini-control-plane.yml"));
        Map<?, ?> macSpring = map(macmini, "spring");
        Map<?, ?> macOpsLedger = map(map(macmini, "rag"), "ops-ledger");
        Map<?, ?> macAwxNode = map(map(macmini, "awx"), "node");
        Map<?, ?> macGpuGateway = map(map(macmini, "awx"), "gpu-gateway");
        Map<?, ?> macCollector = map(map(map(macmini, "awx"), "learning-ops"), "collector");
        Map<?, ?> macAutolearn = map(map(macmini, "uaw"), "autolearn");
        Map<?, ?> macUawNode = map(macAutolearn, "runtime-node");
        Map<?, ?> macRouterModels = map(map(macmini, "llmrouter"), "models");

        assertEquals("${MACMINI_SQL_INIT_MODE:never}", map(map(macSpring, "sql"), "init").get("mode"));
        assertEquals("${MACMINI_DB_URL:jdbc:h2:mem:lms_macmini;MODE=MariaDB;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false}",
                map(macSpring, "datasource").get("url"));
        assertEquals("${MACMINI_RAG_OPS_LEDGER_ENABLED:true}", macOpsLedger.get("enabled"));
        assertEquals("${MACMINI_RAG_OPS_LEDGER_CAPTURE_RAG:true}", macOpsLedger.get("capture-rag"));
        assertEquals("${MACMINI_RAG_OPS_LEDGER_CAPTURE_AUTOLEARN:true}", macOpsLedger.get("capture-autolearn"));
        assertEquals("macmini-control-plane", macAwxNode.get("role"));
        assertEquals(Boolean.TRUE, macAwxNode.get("control-plane"));
        assertEquals(Boolean.FALSE, macAwxNode.get("heavy-workloads-allowed"));
        assertEquals("${MACMINI_DESKTOP_GPU_GATEWAY_ENABLED:false}", macGpuGateway.get("enabled"));
        assertEquals("desktop-rtx3090-rtx3060", macGpuGateway.get("target-execution-node"));
        assertEquals("${MACMINI_DESKTOP_GPU_3090_BASE_URL:${LLM_3090_BASE_URL:}}", macGpuGateway.get("primary-chat-base-url"));
        assertEquals("${MACMINI_DESKTOP_GPU_3060_BASE_URL:${LLM_3060_BASE_URL:}}", macGpuGateway.get("fast-base-url"));
        assertEquals("${MACMINI_DESKTOP_GPU_EMBED_BASE_URL:${EMBED_3060_BASE_URL:}}", macGpuGateway.get("embedding-base-url"));
        assertEquals("${MACMINI_DESKTOP_GPU_ALLOWED_HOSTS:${LLM_PROVIDER_GUARD_ALLOWED_HOSTS:}}", macGpuGateway.get("allowed-hosts"));
        assertEquals("${MACMINI_LEARNING_OPS_COLLECTOR_ENABLED:true}", macCollector.get("enabled"));
        assertEquals("${MACMINI_LEARNING_OPS_COLLECTOR_OUTPUT_PATH:data/macmini/learning-ops-curation.jsonl}", macCollector.get("output-path"));
        assertEquals("${MACMINI_LEARNING_OPS_COLLECTOR_MAX_ITEMS:20}", macCollector.get("max-items"));
        assertEquals(Boolean.FALSE, map(macRouterModels, "gemma").get("enabled"));
        assertEquals(Boolean.FALSE, map(macRouterModels, "light").get("enabled"));
        assertEquals("observe-curate-schedule", map(macRouterModels, "macmini").get("workload"));
        assertEquals(Boolean.FALSE, map(macAutolearn, "retrain").get("enabled"));
        assertEquals("curate-observe-schedule", macUawNode.get("learning-loop-mode"));
    }

    @Test
    void applicationPropertiesKeepLocalUiAndCurationGuardDefaults() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("main/resources/application.properties"))) {
            props.load(in);
        }

        assertEquals("${llm.fast.model:qwen3.5:9b}", props.getProperty("app.ai.ui-default-model"));
        assertEquals("false", props.getProperty("app.ai.allow-remote-model-selection"));
        assertEquals("2", props.getProperty("agent.knowledge-curation.min-entity-codepoints"));
        assertEquals("e,unknown,n/a,na,none,null", props.getProperty("agent.knowledge-curation.blocked-entities"));
        assertEquals("true", props.getProperty("agent.knowledge-curation.reject-verification-needed"));
        assertEquals("false", props.getProperty("awx.learning-ops.collector.enabled"));
        assertEquals("data/macmini/learning-ops-curation.jsonl", props.getProperty("awx.learning-ops.collector.output-path"));
        assertEquals("20", props.getProperty("awx.learning-ops.collector.max-items"));
        assertEquals("${LMS_JPA_DDL_AUTO:validate}", props.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("${SPRING_JPA_SHOW_SQL:false}", props.getProperty("spring.jpa.show-sql"));
        assertEquals("${TAVILY_ENABLED:false}", props.getProperty("tavily.enabled"));
    }

    private static void assertLocalApplicationRoles(Path path) throws IOException {
        Map<?, ?> root = loadMap(path);
        Map<?, ?> llm = map(root, "llm");
        Map<?, ?> models = map(llm, "models");

        assertEquals("${LLM_CHAT_MODEL:gemma4:26b}", llm.get("chat-model"));
        assertEquals("${LLM_CHAT_MODEL:gemma4:26b}", map(models, "gemma4_26b").get("name"));
        assertEquals("${LLM_GEMMA3_4B_MODEL:gemma4:12b}", map(models, "gemma3_4b").get("name"));
        assertEquals("${LLM_JUDGE_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}", map(models, "qwen3_30b").get("name"));
        assertEquals("${LLM_CODER_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}", map(models, "qwen3_coder_30b").get("name"));
        assertEquals("${LLM_FAST_MODEL:qwen3.5:9b}", map(models, "qwen3_8b").get("name"));
        assertEquals("${LLM_VISION_MODEL:qwen3-vl:8b}", map(models, "qwen3_vl_8b").get("name"));
        assertLocalGpuPlacement(models);
        Map<?, ?> routeTp = map(map(llm, "route"), "tp");
        List<?> endpoints = list(routeTp, "endpoints");
        assertEquals("desktop-local", ((Map<?, ?>) endpoints.get(0)).get("id"));
    }

    private static void assertLocalGpuPlacement(Map<?, ?> models) {
        assertEquals("rtx3090", map(models, "gemma4_26b").get("gpu"));
        assertEquals("rtx3060", map(models, "gemma3_4b").get("gpu"));
        assertEquals("rtx3090", map(models, "qwen3_30b").get("gpu"));
        assertEquals("rtx3090", map(models, "qwen3_coder_30b").get("gpu"));
        assertEquals("rtx3060", map(models, "qwen3_8b").get("gpu"));
        assertEquals("rtx3060", map(models, "qwen3_vl_8b").get("gpu"));
    }

    private static void assertLocalManifest(Path path) throws IOException {
        ModelsManifest manifest;
        try (InputStream in = Files.newInputStream(path)) {
            manifest = new Yaml().loadAs(in, ModelsManifest.class);
        }

        assertNotNull(manifest);
        assertEquals("gemma4:26b", manifest.getBindings().getDefault());
        assertEquals("gemma4:26b", manifest.getBindings().getMoe());
        assertEquals("qwen3.5:9b", manifest.getAliases().get("cheap"));
        assertEquals("qwen3.5:9b", manifest.getAliases().get("fast"));
        assertEquals("qwen3-vl:8b", manifest.getAliases().get("vision"));
        assertEquals("smtek/Qwen3.8-27B:Q3_K_XL", manifest.getAliases().get("judge"));
        assertEquals("smtek/Qwen3.8-27B:Q3_K_XL", manifest.getAliases().get("critic"));
        assertEquals("smtek/Qwen3.8-27B:Q3_K_XL", manifest.getAliases().get("coder"));
        assertEquals("gemma4:26b", manifest.getAliases().get("gemma4-26b"));
        assertFalse(manifest.getAliases().containsKey("gemma4:26b"));
        assertFalse(manifest.getAliases().containsKey("gemma3:27b"));
        assertFalse(manifest.getAliases().containsKey("qwen3:8b"));

        ModelsManifest.Model embedding = modelById(manifest, "qwen3-embedding:4b");
        assertTrue(embedding.getCapabilities().contains("embedding"));
        assertFalse(embedding.getCapabilities().contains("chat"));
        assertEquals("${EMBED_BASE_URL:${EMBED_3060_BASE_URL:http://127.0.0.1:11435/api/embed}}",
                embedding.getEndpoint().base_url);

        ModelsManifest.Model fast = modelById(manifest, "qwen3.5:9b");
        assertEquals("${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${LLM_BASE_URL:http://127.0.0.1:11435/v1}}}",
                fast.getEndpoint().base_url);

        ModelsManifest.Model vision = modelById(manifest, "qwen3-vl:8b");
        assertTrue(vision.getCapabilities().contains("vision"));
        assertTrue(vision.getCapabilities().contains("chat"));
        assertEquals("${LLM_VISION_BASE_URL:${LLM_3060_BASE_URL:${LLM_BASE_URL:http://127.0.0.1:11435/v1}}}",
                vision.getEndpoint().base_url);

        ModelsManifest.Model chat = modelById(manifest, "gemma4:26b");
        assertTrue(chat.getCapabilities().contains("chat"));
        assertFalse(chat.getCapabilities().contains("vision"));
        assertEquals("${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11434/v1}}",
                chat.getEndpoint().base_url);

        ModelsManifest.Model judge = modelByCapability(manifest, "judge");
        assertEquals("${LLM_JUDGE_BASE_URL:${LLM_3090_BASE_URL:${LLM_BASE_URL:http://127.0.0.1:11434/v1}}}",
                judge.getEndpoint().base_url);

        ModelsManifest.Model coder = modelByCapability(manifest, "code");
        assertEquals("${LLM_CODER_BASE_URL:${LLM_3090_BASE_URL:${LLM_BASE_URL:http://127.0.0.1:11434/v1}}}",
                coder.getEndpoint().base_url);
    }

    private static ModelsManifest.Model modelById(ModelsManifest manifest, String id) {
        return manifest.getModels().stream()
                .filter(model -> id.equals(model.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing model " + id));
    }

    private static ModelsManifest.Model modelByCapability(ModelsManifest manifest, String capability) {
        return manifest.getModels().stream()
                .filter(model -> model.getCapabilities() != null && model.getCapabilities().contains(capability))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing model capability " + capability));
    }

    private static void assertDisabledCloudRoute(Map<?, ?> models, String routeKey, String provider, String credentialEnv) {
        Map<?, ?> route = map(models, routeKey);
        assertEquals("${LLMROUTER_" + routeKey.toUpperCase().replace('-', '_') + "_ENABLED:false}", route.get("enabled"));
        assertEquals(provider, route.get("provider"));
        assertEquals(Boolean.TRUE, route.get("fallback-only"));
        assertEquals("${LLMROUTER_" + routeKey.toUpperCase().replace('-', '_') + "_WEIGHT:0.0}", route.get("weight"));
        assertEquals(credentialEnv, route.get("credential-env"));
    }

    private static Map<?, ?> loadMap(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);
            return (Map<?, ?>) loaded;
        }
    }

    private static Map<?, ?> map(Map<?, ?> source, String key) {
        return (Map<?, ?>) source.get(key);
    }

    private static List<?> list(Map<?, ?> source, String key) {
        return (List<?>) source.get(key);
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
