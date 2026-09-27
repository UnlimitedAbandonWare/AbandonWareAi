package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.orch.ecosystem.EcosystemBufferPool;
import ai.abandonware.nova.orch.web.WebSnippet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaAutoConfigurationSourceContractTest {

    @Test
    void autoConfigurationImportsExposeFailurePatternAndZero100Candidates() {
        List<String> candidates = ImportCandidates
                .load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();

        assertTrue(candidates.contains(NovaFailurePatternAutoConfiguration.class.getName()),
                "failure-pattern auto-configuration must be discoverable by Spring Boot");
        assertTrue(candidates.contains(NovaZero100AutoConfiguration.class.getName()),
                "Zero100 auto-configuration must be discoverable by Spring Boot");
    }

    @Test
    void failurePatternCooldownDiagnosticsDoesNotRequireTraceStoreBean() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaFailurePatternAutoConfiguration.java"));

        assertTrue(source.contains("FailurePatternCooldownDiagnosticsAspect failurePatternCooldownDiagnosticsAspect"));
        assertTrue(source.contains("@ConditionalOnProperty(name = \"nova.orch.failure.cooldown-diagnostics.enabled\""));
        assertTrue(!source.contains("@ConditionalOnBean(TraceStore.class)"));
    }

    @Test
    void ragCompressionAspectIsEnabledWhenPropertyIsMissing() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java"));

        assertTrue(source.contains("@ConditionalOnProperty(name = \"nova.orch.rag-compressor.enabled\", havingValue = \"true\", matchIfMissing = true)"));
        assertTrue(source.indexOf("matchIfMissing = true")
                < source.indexOf("public RagCompressionAspect ragCompressionAspect"));
    }

    @Test
    void webSoakMaxRecentParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java"));
        String parserCall = "maxRecent = Integer.parseInt(v.trim());";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "web soak max-recent parser call should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 220));
        assertFalse(window.contains("catch (Exception"),
                "web soak max-recent parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "web soak max-recent parser must not swallow Throwable");
        assertTrue(window.contains("catch (NumberFormatException"),
                "web soak max-recent parser should only catch NumberFormatException");
        assertTrue(window.contains("traceWebSoakMaxRecentParseFallback(parseError)"),
                "web soak max-recent parser fallback should leave a redacted breadcrumb");
    }

    @Test
    void webSoakMaxRecentParseFallbackUsesStableInvalidNumberLabel() throws Exception {
        java.lang.reflect.Method method = NovaOrchestrationAutoConfiguration.class.getDeclaredMethod(
                "errorType", Throwable.class);
        method.setAccessible(true);

        assertEquals("invalid_number", method.invoke(null, new NumberFormatException("private-token")));
    }

    @Test
    void providerRateLimitBackoffAspectRequiresWebClientClass() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaOpsStabilizationAutoConfiguration.java"));

        assertTrue(source.contains("import org.springframework.web.reactive.function.client.WebClient;"));
        assertTrue(source.contains("@ConditionalOnClass(WebClient.class)"));
        assertTrue(source.indexOf("@ConditionalOnClass(WebClient.class)")
                < source.indexOf("public ProviderRateLimitBackoffAspect providerRateLimitBackoffAspect"));
    }

    @Test
    void beanPostProcessorFactoriesAreStaticToAvoidEarlyAutoConfigurationInstantiation() throws Exception {
        String debug = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaDebugPortAutoConfiguration.java"));
        String ops = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaOpsStabilizationAutoConfiguration.java"));

        assertTrue(debug.contains(
                "public static ExecutorServiceContextPropagationPostProcessor executorServiceContextPropagationPostProcessor"),
                "ExecutorService BeanPostProcessor should use a static @Bean factory to avoid eager auto-config instantiation");
        assertTrue(ops.contains(
                "public static CancelShieldExecutorServicePostProcessor cancelShieldExecutorServicePostProcessor"),
                "CancelShield BeanPostProcessor should use a static @Bean factory to avoid eager auto-config instantiation");
        assertTrue(ops.contains(
                "public static MatryoshkaEmbeddingModelPostProcessor matryoshkaEmbeddingModelPostProcessor"),
                "Matryoshka BeanPostProcessor should use a static @Bean factory to avoid eager auto-config instantiation");

        String orchestration = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java"));
        assertTrue(orchestration.contains(
                "public static BraveAdaptiveQpsInstaller braveAdaptiveQpsInstaller"),
                "Brave adaptive-QPS BeanPostProcessor should use a static @Bean factory to avoid eager auto-config instantiation");
        assertTrue(orchestration.contains(
                "public static BraveRestTemplateTimeoutOverrideInstaller braveRestTemplateTimeoutOverrideInstaller"),
                "Brave RestTemplate timeout BeanPostProcessor should use a static @Bean factory to avoid eager auto-config instantiation");
        assertTrue(orchestration.contains(
                "public static ChatWorkflowFastBailoutMinHitsPostProcessor chatWorkflowFastBailoutMinHitsPostProcessor"),
                "ChatWorkflow fast-bailout BeanPostProcessor should use a static @Bean factory to avoid eager auto-config instantiation");
    }

    @Test
    void beanPostProcessorFactoriesKeepOptionalDependenciesLazy() throws Exception {
        String debug = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaDebugPortAutoConfiguration.java"));
        String orchestration = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java"));

        assertFalse(debug.contains("new ExecutorServiceContextPropagationPostProcessor(env, debugEventStoreProvider.getIfAvailable())"),
                "ExecutorService BPP factory must not eagerly resolve DebugEventStore while the BPP is being created");
        assertTrue(debug.contains("ExecutorServiceContextPropagationPostProcessor.withLazyDebugEventStore("),
                "ExecutorService BPP should defer DebugEventStore resolution until it emits telemetry");

        assertTrue(orchestration.contains("ObjectProvider<NovaBraveAdaptiveQpsProperties> propsProvider"),
                "Brave adaptive-QPS BPP should receive properties through ObjectProvider");
        assertTrue(orchestration.contains("ObjectProvider<BraveRateLimitState> braveRateLimitStateProvider"),
                "Brave adaptive-QPS BPP should receive state through ObjectProvider");
        assertTrue(orchestration.contains("new BraveAdaptiveQpsInstaller(propsProvider::getIfAvailable, braveRateLimitStateProvider::getIfAvailable)"),
                "Brave adaptive-QPS BPP should defer properties and state resolution until it sees the Brave service bean");
    }

    @Test
    void requiredOperationalAspectsRemainRegisteredInAutoConfiguration() throws Exception {
        String orchestration = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java"));
        String ops = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/autoconfig/NovaOpsStabilizationAutoConfiguration.java"));

        assertTrue(orchestration.contains("public OpenAiChatModelGuardAspect openAiChatModelGuardAspect"));
        assertTrue(orchestration.contains("public LlmRouterAspect llmRouterAspect"));
        assertTrue(orchestration.contains("public WebFailSoftSearchAspect webFailSoftSearchAspect"));
        assertTrue(orchestration.contains("public HybridWebSearchInterruptHygieneAspect hybridWebSearchInterruptHygieneAspect"));
        assertTrue(orchestration.contains("public NaverInterruptHygieneAspect naverInterruptHygieneAspect"));
        assertTrue(orchestration.contains("public EvidenceListTraceInjectionAspect evidenceListTraceInjectionAspect"));
        assertTrue(orchestration.contains("public EvidenceListSnippetFallbackAspect evidenceListSnippetFallbackAspect"));
        assertTrue(ops.contains("public HybridWebSearchEmptyFallbackAspect hybridWebSearchEmptyFallbackAspect"));
        assertTrue(ops.contains("public static CancelShieldExecutorServicePostProcessor cancelShieldExecutorServicePostProcessor"));
    }

    @Test
    void ecosystemBufferPoolReadsCanonicalPoolProperties() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("nova.ecosystem.pool.max-size", "2")
                .withProperty("nova.ecosystem.pool.ttl-ms", "1000")
                .withProperty("nova.ecosystem.pool.recirculate-max", "5")
                .withProperty("nova.ecosystem.pool.ammonia-threshold", "0.25")
                .withProperty("nova.ecosystem.pool.quarantine-tag", "QUARANTINED");

        EcosystemBufferPool pool = new NovaOrchestrationAutoConfiguration().ecosystemBufferPool(env);
        pool.charge("rid-contract", List.of(
                WebSnippet.parse("First https://docs.example/one"),
                WebSnippet.parse("Second https://docs.example/two"),
                WebSnippet.parse("Third https://docs.example/three")));

        assertEquals(2, pool.poolSize());
        assertEquals(5, pool.defaultRecirculateMax());
        assertEquals(0.25d, pool.ammoniaThreshold());
        assertEquals("QUARANTINED", pool.quarantineTag());
    }

    @Test
    void applicationYmlExposesCanonicalEcosystemPoolProperties() throws Exception {
        String yml = Files.readString(Path.of("main/resources/application.yml"));

        assertTrue(yml.contains("pool:"));
        assertTrue(yml.contains("NOVA_ECOSYSTEM_POOL_MAX_SIZE"));
        assertTrue(yml.contains("NOVA_ECOSYSTEM_POOL_TTL_MS"));
        assertTrue(yml.contains("NOVA_ECOSYSTEM_POOL_RECIRCULATE_MAX"));
        assertTrue(yml.contains("NOVA_ECOSYSTEM_POOL_AMMONIA_THRESHOLD"));
        assertTrue(yml.contains("NOVA_ECOSYSTEM_POOL_QUARANTINE_TAG"));
    }
}
