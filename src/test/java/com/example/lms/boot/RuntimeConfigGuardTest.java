package com.example.lms.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void prodRiskSettingsAreViolations() {
        MockEnvironment env = riskyEnvironment("prod");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.strict());
        Set<String> properties = evaluation.findings().stream()
                .map(RuntimeConfigGuard.Finding::property)
                .collect(Collectors.toSet());
        Set<String> classifications = evaluation.findings().stream()
                .map(RuntimeConfigGuard.Finding::classification)
                .collect(Collectors.toSet());
        assertTrue(properties.contains("management.endpoints.web.exposure.include"));
        assertTrue(properties.contains("management.endpoint.env.show-values"));
        assertTrue(properties.contains("lms.debug.prompts.dump"));
        assertTrue(properties.contains("lms.cors.allowed-origin-patterns"));
        assertTrue(properties.contains("server.ssl.key-store"));
        assertTrue(properties.contains("spring.datasource.password"));
        assertTrue(properties.contains("domain.allowlist.admin-token"));
        assertTrue(properties.contains("security.admin-secret"));
        assertTrue(properties.contains("security.remember-me-key"));
        assertTrue(properties.contains("security.bootstrap-admin.password"));
        assertTrue(properties.contains("train_idle.enabled"));
        assertTrue(properties.contains("autolearn.enabled"));
        assertTrue(properties.contains("uaw.autolearn.enabled"));
        assertTrue(properties.contains("local-llm.enabled"));
        assertTrue(properties.contains("local-llm.autostart"));
        assertTrue(properties.contains("selfask.enabled"));
        assertTrue(properties.contains("tavily.enabled"));
        assertTrue(properties.contains("onnx.model-path"));
        assertTrue(classifications.contains("actuator-exposure"));
        assertTrue(classifications.contains("secret-required"));
        assertTrue(classifications.contains("background-workload"));
        assertTrue(classifications.contains("local-model-risk"));
    }

    @Test
    void localRiskSettingsWarnOnly() {
        MockEnvironment env = riskyEnvironment("local");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertFalse(evaluation.strict());
        assertFalse(evaluation.findings().isEmpty());
    }

    @Test
    void productionAliasIsStrictAndCannotDisableGuard() {
        MockEnvironment env = riskyEnvironment("production");
        env.withProperty("runtime.config.guard.enabled", "false");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.enabled());
        assertTrue(evaluation.strict());
        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "runtime.config.guard.enabled".equals(f.property())
                        && "guard_disable_ignored_in_production".equals(f.reason())));
    }

    @Test
    void liveAndProdFamilyProfilesAreStrict() {
        for (String profile : new String[]{"live", "live-blue", "prod-blue", "production-eu"}) {
            RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(riskyEnvironment(profile));
            assertTrue(evaluation.strict(), profile);
        }
    }

    @Test
    void explicitStrictFlagFailsClosedOutsideProd() {
        MockEnvironment env = riskyEnvironment("local");
        env.withProperty("runtime.config.guard.strict", "true");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.strict());
        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "train_idle.enabled".equals(f.property())));
    }

    @Test
    void strictProfileFlagsSelfExitingSoakQuickRunner() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.withProperty("soak.enabled", "true")
                .withProperty("soak.quick-runner.enabled", "true")
                .withProperty("soak.quick-runner.cli", "true");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "soak.quick-runner.exit-after-run".equals(f.property())
                        && "soak_quick_runner_exit_enabled_in_strict_profile".equals(f.reason())));
    }

    @Test
    void onnxClasspathPlaceholderModelPathIsNotAcceptedAsRealModel() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.withProperty("management.endpoints.web.exposure.include", "health,info")
                .withProperty("management.endpoint.env.show-values", "NEVER")
                .withProperty("management.endpoint.httptrace.enabled", "false")
                .withProperty("lms.debug.prompts.dump", "false")
                .withProperty("lms.debug.responses.dump", "false")
                .withProperty("lms.debug.mask-secrets", "true")
                .withProperty("lms.cors.allow-credentials", "false")
                .withProperty("server.ssl.enabled", "false")
                .withProperty("spring.datasource.password", "db-secret")
                .withProperty("domain.allowlist.admin-token", "admin-secret")
                .withProperty("security.admin-secret", "admin-session-secret")
                .withProperty("security.remember-me-key", "remember-secret")
                .withProperty("security.bootstrap-admin.password", "bootstrap-secret")
                .withProperty("probe.search.enabled", "false")
                .withProperty("train_idle.enabled", "false")
                .withProperty("autolearn.enabled", "false")
                .withProperty("uaw.autolearn.enabled", "false")
                .withProperty("local-llm.enabled", "false")
                .withProperty("local-llm.autostart", "false")
                .withProperty("selfask.enabled", "false")
                .withProperty("tavily.enabled", "false")
                .withProperty("onnx.enabled", "true")
                .withProperty("zsys.onnx.enabled", "false")
                .withProperty("abandonware.reranker.onnx.runtime-enabled", "false")
                .withProperty("abandonware.reranker.onnx.model-path", "classpath:models/your-cross-encoder.onnx");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "onnx.model-path".equals(f.property())
                        && "placeholder_or_too_small_model".equals(f.reason())));
    }

    @Test
    void onnxTinyModelFileIsNotAcceptedAsRealModel() throws Exception {
        Path tinyModel = tempDir.resolve("candidate.onnx");
        Files.writeString(tinyModel, "placeholder");

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.withProperty("management.endpoints.web.exposure.include", "health,info")
                .withProperty("management.endpoint.env.show-values", "NEVER")
                .withProperty("management.endpoint.httptrace.enabled", "false")
                .withProperty("lms.debug.prompts.dump", "false")
                .withProperty("lms.debug.responses.dump", "false")
                .withProperty("lms.debug.mask-secrets", "true")
                .withProperty("lms.cors.allow-credentials", "false")
                .withProperty("server.ssl.enabled", "false")
                .withProperty("spring.datasource.password", "db-secret")
                .withProperty("domain.allowlist.admin-token", "admin-secret")
                .withProperty("security.admin-secret", "admin-session-secret")
                .withProperty("security.remember-me-key", "remember-secret")
                .withProperty("security.bootstrap-admin.password", "bootstrap-secret")
                .withProperty("probe.search.enabled", "false")
                .withProperty("train_idle.enabled", "false")
                .withProperty("autolearn.enabled", "false")
                .withProperty("uaw.autolearn.enabled", "false")
                .withProperty("local-llm.enabled", "false")
                .withProperty("local-llm.autostart", "false")
                .withProperty("selfask.enabled", "false")
                .withProperty("tavily.enabled", "false")
                .withProperty("onnx.enabled", "true")
                .withProperty("zsys.onnx.enabled", "false")
                .withProperty("abandonware.reranker.onnx.runtime-enabled", "false")
                .withProperty("abandonware.reranker.onnx.model-path", tinyModel.toString());

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "onnx.model-path".equals(f.property())
                        && "placeholder_or_too_small_model".equals(f.reason())));
    }

    @Test
    void prodGuardCannotBeBypassedByAuxiliaryProfiles() {
        MockEnvironment env = riskyEnvironment("prod");
        env.setActiveProfiles("prod", "ultra", "proxy", "local-llm");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.strict());
        assertTrue(evaluation.profileLabel().contains("prod"));
        assertFalse(evaluation.findings().isEmpty());
    }

    @Test
    void prodSafeBaselineHasNoFindings() {
        MockEnvironment env = safeProductionEnvironment();

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.strict());
        assertTrue(evaluation.findings().isEmpty(), evaluation.findings().toString());
    }

    private static MockEnvironment safeProductionEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.withProperty("management.endpoints.web.exposure.include", "health,info")
                .withProperty("management.endpoint.env.show-values", "NEVER")
                .withProperty("management.endpoint.httptrace.enabled", "false")
                .withProperty("lms.debug.prompts.dump", "false")
                .withProperty("lms.debug.responses.dump", "false")
                .withProperty("lms.debug.mask-secrets", "true")
                .withProperty("lms.cors.allow-credentials", "false")
                .withProperty("server.ssl.enabled", "false")
                .withProperty("security.force-https", "true")
                .withProperty("security.tls-offload.enabled", "true")
                .withProperty("server.forward-headers-strategy", "framework")
                .withProperty("server.address", "127.0.0.1")
                .withProperty("spring.datasource.password", "db-secret")
                .withProperty("domain.allowlist.admin-token", "admin-secret")
                .withProperty("security.admin-secret", "admin-session-secret")
                .withProperty("security.remember-me-key", "remember-secret")
                .withProperty("security.bootstrap-admin.password", "bootstrap-secret")
                .withProperty("probe.search.enabled", "false")
                .withProperty("train_idle.enabled", "false")
                .withProperty("autolearn.enabled", "false")
                .withProperty("uaw.autolearn.enabled", "false")
                .withProperty("local-llm.enabled", "false")
                .withProperty("local-llm.autostart", "false")
                .withProperty("selfask.enabled", "false")
                .withProperty("tavily.enabled", "false")
                .withProperty("onnx.enabled", "false")
                .withProperty("zsys.onnx.enabled", "false")
                .withProperty("abandonware.reranker.onnx.runtime-enabled", "false");
        return env;
    }

    @Test
    void prodRejectsPlainHttpWithoutExplicitTlsOffload() {
        MockEnvironment env = riskyEnvironment("prod");
        env.withProperty("server.ssl.enabled", "false")
                .withProperty("security.force-https", "false")
                .withProperty("security.tls-offload.enabled", "false");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "security.tls-offload.enabled".equals(f.property())
                        && "production_tls_boundary_missing".equals(f.reason())
                        && "ssl-risk".equals(f.classification())));
    }

    @Test
    void prodRejectsOffloadWithoutEnforcedTrustedProxyBoundary() {
        MockEnvironment env = riskyEnvironment("prod");
        env.withProperty("server.ssl.enabled", "false")
                .withProperty("security.force-https", "true")
                .withProperty("security.tls-offload.enabled", "true")
                .withProperty("server.forward-headers-strategy", "framework")
                .withProperty("server.address", "0.0.0.0");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "server.address".equals(f.property())
                        && "tls_offload_backend_not_loopback".equals(f.reason())
                        && "ssl-risk".equals(f.classification())));
    }

    @Test
    void prodRejectsEmbeddedTlsWhenPlainConnectorIsNotRedirected() {
        MockEnvironment env = riskyEnvironment("prod");
        env.withProperty("server.ssl.enabled", "true")
                .withProperty("security.force-https", "false");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "security.force-https".equals(f.property())
                        && "direct_tls_plain_http_not_redirected".equals(f.reason())
                        && "ssl-risk".equals(f.classification())));
    }

    @Test
    void prodRejectsDirectTlsWhenForwardHeadersRemainEnabled() {
        MockEnvironment env = safeDirectTlsEnvironment();
        env.withProperty("server.forward-headers-strategy", "framework");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "server.forward-headers-strategy".equals(f.property())
                        && "direct_tls_forward_headers_enabled".equals(f.reason())
                        && "ssl-risk".equals(f.classification())));
    }

    @Test
    void prodAllowsDirectTlsWithoutDistinctKeyPasswordWhenForwardHeadersAreDisabled() {
        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(safeDirectTlsEnvironment());

        assertFalse(evaluation.findings().stream()
                .anyMatch(f -> "ssl-risk".equals(f.classification())), evaluation.findings().toString());
    }

    @Test
    void prodRejectsNativeProxyPatternsThatMatchPublicAddresses() {
        for (String pattern : new String[]{"^.*$", "(?s).*", ".+", "^(?!8\\.8\\.8\\.8$)(?!203\\.0\\.113\\.10$).+$"}) {
            MockEnvironment env = safeProductionEnvironment();
            env.withProperty("server.forward-headers-strategy", "native")
                    .withProperty("server.tomcat.remoteip.internal-proxies", pattern);

            RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

            assertTrue(evaluation.findings().stream()
                    .anyMatch(f -> "server.tomcat.remoteip.internal-proxies".equals(f.property())
                            && "tls_offload_trusted_proxies_unbounded".equals(f.reason())), pattern);
        }
    }

    @Test
    void prodAllowsNativeOffloadOnlyForLoopbackBackendAndExplicitLoopbackProxyPattern() {
        MockEnvironment env = safeProductionEnvironment();
        env.withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.address", "127.0.0.1")
                .withProperty("server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.1");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertFalse(evaluation.findings().stream()
                .anyMatch(f -> "ssl-risk".equals(f.classification())), evaluation.findings().toString());
    }

    @Test
    void prodRejectsNativeOffloadOnNonLoopbackBackendEvenWithNarrowProxyPattern() {
        MockEnvironment env = safeProductionEnvironment();
        env.withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.address", "0.0.0.0")
                .withProperty("server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.1");

        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(env);

        assertTrue(evaluation.findings().stream()
                .anyMatch(f -> "server.address".equals(f.property())
                        && "tls_offload_backend_not_loopback".equals(f.reason())));
    }

    private static MockEnvironment safeDirectTlsEnvironment() {
        MockEnvironment env = safeProductionEnvironment();
        env.withProperty("server.ssl.enabled", "true")
                .withProperty("security.tls-offload.enabled", "false")
                .withProperty("security.force-https", "true")
                .withProperty("server.forward-headers-strategy", "none")
                .withProperty("server.ssl.key-store", "file:C:/certs/app.p12")
                .withProperty("server.ssl.key-store-password", "store-secret")
                .withProperty("server.ssl.key-password", "");
        return env;
    }

    @Test
    void onnxPathParserFallbackEmitsNamedBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/boot/RuntimeConfigGuard.java"));

        assertTrue(source.contains("traceSuppressed(\"runtimeConfig.onnxModelPath\", ignore);"));
    }

    @Test
    void prodGuardRejectsSchemeWideCorsWildcardWithCredentials() {
        for (String pattern : new String[]{"http://*", "https://*", "http://*:[8080]", "https://*:[*]"}) {
            RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(corsEnvironment(pattern));

            assertTrue(evaluation.findings().stream()
                    .anyMatch(f -> "lms.cors.allowed-origin-patterns".equals(f.property())
                            && "wildcard_cors_with_credentials".equals(f.reason())
                            && "cors-risk".equals(f.classification())));
        }
    }

    @Test
    void prodGuardAllowsBoundedSubdomainCorsPatternWithCredentials() {
        RuntimeConfigGuard.Evaluation evaluation = RuntimeConfigGuard.evaluate(
                corsEnvironment("https://*.example.test"));

        assertFalse(evaluation.findings().stream()
                .anyMatch(f -> "lms.cors.allowed-origin-patterns".equals(f.property())
                        && "wildcard_cors_with_credentials".equals(f.reason())));
    }

    private static MockEnvironment corsEnvironment(String pattern) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.withProperty("lms.cors.allow-credentials", "true")
                .withProperty("lms.cors.allowed-origin-patterns", pattern);
        return env;
    }

    private static MockEnvironment riskyEnvironment(String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        env.withProperty("management.endpoints.web.exposure.include", "health,env,beans,*")
                .withProperty("management.endpoint.env.show-values", "ALWAYS")
                .withProperty("management.endpoint.httptrace.enabled", "true")
                .withProperty("lms.debug.prompts.dump", "true")
                .withProperty("lms.debug.responses.dump", "true")
                .withProperty("lms.debug.mask-secrets", "false")
                .withProperty("lms.cors.allow-credentials", "true")
                .withProperty("lms.cors.allowed-origin-patterns", "*")
                .withProperty("server.ssl.enabled", "true")
                .withProperty("spring.datasource.password", "dummy")
                .withProperty("domain.allowlist.admin-token", "<TOKEN>")
                .withProperty("security.admin-secret", "changeme")
                .withProperty("security.remember-me-key", "test")
                .withProperty("security.bootstrap-admin.password", "${LMS_ADMIN_BOOTSTRAP_PASSWORD:}")
                .withProperty("probe.search.enabled", "false")
                .withProperty("train_idle.enabled", "true")
                .withProperty("autolearn.enabled", "true")
                .withProperty("uaw.autolearn.enabled", "true")
                .withProperty("local-llm.enabled", "true")
                .withProperty("local-llm.autostart", "true")
                .withProperty("selfask.enabled", "true")
                .withProperty("tavily.enabled", "true")
                .withProperty("onnx.enabled", "true")
                .withProperty("onnx.model.path.cross-encoder", "/models/cross-encoder.onnx");
        return env;
    }
}
