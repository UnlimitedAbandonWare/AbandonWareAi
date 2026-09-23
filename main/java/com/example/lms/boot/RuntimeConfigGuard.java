package com.example.lms.boot;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.guard.OpenAiCredentialPropertyBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class RuntimeConfigGuard implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigGuard.class);
    private static final String PREFIX = "[AWX][runtime-config]";
    private static final Set<String> SAFE_ACTUATOR_ENDPOINTS = Set.of("health", "info");
    private static final long MIN_ONNX_MODEL_BYTES = 1024L * 1024L;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> isolationFailures = verificationFindings(environment, Path.of(System.getProperty("user.dir")));
        if (!isolationFailures.isEmpty()) {
            throw new IllegalStateException("[AWX][verification] blocked properties=" + String.join(",", isolationFailures));
        }
        OpenAiCredentialPropertyBridge.apply(environment);
        Evaluation evaluation = evaluate(environment);
        if (!evaluation.enabled()) {
            return;
        }
        for (Finding finding : evaluation.findings()) {
            log.warn("{} status={} profile={} property={} reason={} classification={}",
                    PREFIX,
                    evaluation.strict() ? "blocked" : "warning",
                    evaluation.profileLabel(),
                    finding.property(),
                    finding.reason(),
                    finding.classification());
        }
        if (evaluation.strict() && !evaluation.findings().isEmpty()) {
            throw new IllegalStateException(PREFIX + " blocked profile=" + evaluation.profileLabel()
                    + " findings=" + summarize(evaluation.findings()));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static Evaluation evaluate(Environment env) {
        Set<String> profiles = profiles(env);
        boolean productionProfile = profiles.stream().anyMatch(RuntimeConfigGuard::isProductionProfile);
        boolean requestedEnabled = bool(env, "runtime.config.guard.enabled", true);
        boolean enabled = productionProfile || requestedEnabled;
        boolean strict = productionProfile || bool(env, "runtime.config.guard.strict", false);
        List<Finding> findings = new ArrayList<>();
        if (productionProfile && !requestedEnabled) {
            findings.add(new Finding("runtime.config.guard.enabled", "guard_disable_ignored_in_production"));
        }
        if (!enabled) {
            return new Evaluation(false, strict, profileLabel(profiles), findings);
        }

        checkActuator(env, findings);
        checkDebugDump(env, findings);
        checkCors(env, findings);
        checkSsl(env, findings, productionProfile);
        checkRequiredSecrets(env, findings);
        checkBackgroundWorkloads(env, findings);
        checkOnnx(env, findings);

        return new Evaluation(true, strict, profileLabel(profiles), findings);
    }


    // Verification is opt-in, but cannot be bypassed by disabling the normal warning guard.
    // Runs after ConfigData and before any datasource, scheduler or process-manager bean.
    static List<String> verificationFindings(Environment env, Path workingDirectory) {
        List<String> failures = new ArrayList<>();
        if (!profiles(env).contains("verification")
                && !bool(env, "runtime.verification.enabled", false)) return failures;
        for (String key : List.of(
                "local-llm.enabled",
                "local-llm.autostart",
                "local-llm.warmup.enabled",
                "local-llm.cuda-auto-discover",
                "onnx.enabled",
                "zsys.onnx.enabled",
                "ocr.enabled",
                "rag.ocr.enabled",
                "abandonware.reranker.onnx.runtime-enabled",
                "llm.gateway.probe.enabled",
                "llm.gateway.local-device-failover.enabled",
                "llmrouter.models.api3.enabled",
                "llmrouter.models.openai-premium.enabled",
                "llmrouter.models.openai-balanced.enabled",
                "llmrouter.models.gemini-pro.enabled",
                "llmrouter.models.mistral-medium.enabled",
                "llmrouter.models.macmini.enabled",
                "llmrouter.models.external.enabled",
                "gpt-search.gemini.enabled",
                "gpt-search.serpapi.enabled",
                "tavily.enabled",
                "selfask.enabled",
                "embedding.fast-fail.health.enabled",
                "embedding.fallback.enabled",
                "embedding.cross-gpu-fallback.enabled",
                "embedding.port-fallback.enabled",
                "upstash.vector.enabled",
                "vector.upstash.enabled",
                "vector.upstash.write-enabled",
                "vectorstore.flush.scheduler.enabled",
                "vector.dlq.health-probe.enabled",
                "vector.dlq.redrive.enabled",
                "management.health.neo4j.enabled",
                "retrieval.kg.neo4j.enabled",
                "graphdb.manual-learning.enabled",
                "graphdb.manual-learning.neo4j-enabled",
                "train_idle.enabled",
                "autolearn.enabled",
                "uaw.autolearn.enabled",
                "rgb.moe.autoevolve.enabled",
                "nova.orch.degraded-storage.enabled",
                "awx.learning-ops.collector.enabled",
                "awx.gpu-gateway.enabled",
                "macmini.gpu.telemetry.enabled",
                "macmini.gpu.admission.enabled",
                "desktop.gpu.telemetry.enabled",
                "desktop.gpu.admission.enabled",
                "probe.search.enabled",
                "probe.soak.enabled",
                "soak.enabled",
                "spring.flyway.enabled",
                "spring.liquibase.enabled",
                "lms.debug.prompts.dump",
                "lms.debug.responses.dump")) {
            if (!"false".equalsIgnoreCase(prop(env, key, ""))) failures.add(key + ":must_be_disabled");
        }
        for (String key : List.of(
                "upstash.vector.rest-url",
                "upstash.vector.api-key",
                "vector.upstash.url",
                "vector.upstash.token",
                "upstash.redis.rest-url",
                "upstash.redis.rest-token",
                "retrieval.kg.neo4j.uri",
                "retrieval.kg.neo4j.user",
                "retrieval.kg.neo4j.password",
                "naver.keys",
                "naver.client-id",
                "naver.client-secret",
                "openai.api-key",
                "llm.api-key-openai",
                "llm.openai.api-key",
                "llm.owner-token",
                "spring.datasource.hikari.connection-init-sql",
                "spring.config.additional-location")) {
            if (!prop(env, key, "").isEmpty()) failures.add(key + ":must_be_empty");
        }
        String runId = prop(env, "verification.run-id", "");
        if (!runId.matches("[a-f0-9]{32}")) failures.add("verification.run-id:invalid");
        String expectedDb = "jdbc:h2:mem:verification_" + runId
                + ";MODE=MariaDB;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        verificationEquals(env, failures, "spring.datasource.url", expectedDb);
        verificationEquals(env, failures, "spring.datasource.driver-class-name", "org.h2.Driver");
        verificationEquals(env, failures, "spring.datasource.username", "sa");
        verificationEquals(env, failures, "spring.datasource.password", "");
        verificationEquals(env, failures, "spring.jpa.hibernate.ddl-auto", "create-drop");
        verificationEquals(env, failures, "spring.sql.init.mode", "never");
        verificationEquals(env, failures, "spring.config.import", "optional:classpath:application-llm.yaml");
        verificationEquals(env, failures, "server.address", "127.0.0.1");
        verificationEquals(env, failures, "management.server.address", "127.0.0.1");
        verificationEquals(env, failures, "embedding.provider", "none");
        verificationEquals(env, failures, "vector.store", "memory");
        verificationEquals(env, failures, "llm.provider", "local");
        verificationEquals(env, failures, "llm.api-key", "fixture");
        verificationEquals(env, failures, "gpt-search.brave.api-key", "fixture");
        verificationEquals(env, failures, "llmrouter.enabled", "true");
        // This existing switch installs failover; its target is pinned to the owned fixture, not a cloud host.
        verificationEquals(env, failures, "llm.gateway.cloud.enabled", "true");
        verificationEquals(env, failures, "llm.gateway.cloud.route-key", "light");
        verificationEquals(env, failures, "llmrouter.models.gemma.fallback-key", "light");
        verificationEquals(env, failures, "gpt-search.brave.enabled", "true");
        String fixturePort = prop(env, "verification.fixture-port", "");
        try {
            int port = Integer.parseInt(fixturePort);
            if (port < 1024 || port > 65535) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            failures.add("verification.fixture-port:invalid");
        }
        for (String key : List.of(
                "llm.base-url",
                "llm.fast.base-url",
                "llm.high.base-url",
                "llm.explore.base-url",
                "llm.judge.base-url",
                "llm.coder.base-url",
                "llm.vision.base-url",
                "llm.base-url-qwen",
                "llm.ollama.base-url",
                "local-llm.base-url",
                "llmrouter.models.light.base-url",
                "llmrouter.models.gemma.base-url",
                "llmrouter.models.judge.base-url",
                "llmrouter.models.coder.base-url",
                "llmrouter.models.vision.base-url",
                "llm.route.tp.endpoints[0].url",
                "llm.route.tp.endpoints[1].url",
                "embedding.base-url",
                "gpt-search.brave.base-url")) {
            try {
                URI uri = URI.create(prop(env, key, ""));
                if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                        || !String.valueOf(uri.getPort()).equals(fixturePort)
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null) failures.add(key + ":not_owned_fixture");
            } catch (RuntimeException invalid) {
                failures.add(key + ":invalid_endpoint");
            }
        }
        try {
            Path root = Path.of(prop(env, "verification.root", "")).toRealPath();
            if (!root.equals(workingDirectory.toRealPath())
                    || !Files.readString(root.resolve(".verification-owner")).equals(runId)) {
                failures.add("verification.root:ownership_mismatch");
            }
            for (String key : List.of("llm.gateway.spec-registry.path", "local-llm.log-dir")) {
                Path path = root.resolve(prop(env, key, "")).normalize();
                if (!path.startsWith(root)) failures.add(key + ":outside_owned_root");
            }
        } catch (Exception invalid) {
            failures.add("verification.root:ownership_unproven");
        }
        return List.copyOf(failures);
    }

    private static void verificationEquals(Environment env, List<String> failures, String key, String expected) {
        if (!expected.equals(prop(env, key, ""))) failures.add(key + ":unexpected_value");
    }

    private static void checkActuator(Environment env, List<Finding> findings) {
        Set<String> exposed = csv(prop(env, "management.endpoints.web.exposure.include", "health"));
        if (exposed.contains("*")) {
            findings.add(new Finding("management.endpoints.web.exposure.include", "wildcard_actuator_exposure"));
        }
        for (String endpoint : exposed) {
            if (!SAFE_ACTUATOR_ENDPOINTS.contains(endpoint) && !"*".equals(endpoint)) {
                findings.add(new Finding("management.endpoints.web.exposure.include", "unsafe_actuator_endpoint_" + endpoint));
            }
        }
        if ("always".equalsIgnoreCase(prop(env, "management.endpoint.env.show-values", ""))) {
            findings.add(new Finding("management.endpoint.env.show-values", "env_values_exposed"));
        }
        if (bool(env, "management.endpoint.httptrace.enabled", false)) {
            findings.add(new Finding("management.endpoint.httptrace.enabled", "httptrace_exposed"));
        }
    }

    private static void checkDebugDump(Environment env, List<Finding> findings) {
        if (bool(env, "lms.debug.prompts.dump", false)) {
            findings.add(new Finding("lms.debug.prompts.dump", "prompt_dump_enabled"));
        }
        if (bool(env, "lms.debug.responses.dump", false)) {
            findings.add(new Finding("lms.debug.responses.dump", "response_dump_enabled"));
        }
        if (!bool(env, "lms.debug.mask-secrets", true)) {
            findings.add(new Finding("lms.debug.mask-secrets", "secret_masking_disabled"));
        }
    }

    private static void checkCors(Environment env, List<Finding> findings) {
        boolean credentials = bool(env, "lms.cors.allow-credentials", false);
        Set<String> origins = csv(prop(env, "lms.cors.allowed-origins", ""));
        Set<String> patterns = csv(prop(env, "lms.cors.allowed-origin-patterns", ""));
        if (credentials && (origins.contains("*") || patterns.stream()
                .anyMatch(RuntimeConfigGuard::isSchemeWideWildcardOriginPattern))) {
            findings.add(new Finding("lms.cors.allowed-origin-patterns", "wildcard_cors_with_credentials"));
        }
    }

    private static boolean isSchemeWideWildcardOriginPattern(String pattern) {
        String value = pattern == null ? "" : pattern.trim();
        if ("*".equals(value)) {
            return true;
        }
        int schemeEnd = value.indexOf("://");
        if (schemeEnd <= 0) {
            return false;
        }
        String scheme = value.substring(0, schemeEnd);
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String authority = value.substring(schemeEnd + 3);
        int portStart = authority.indexOf(':');
        String host = portStart >= 0 ? authority.substring(0, portStart) : authority;
        return "*".equals(host);
    }

    private static void checkSsl(Environment env, List<Finding> findings, boolean productionProfile) {
        boolean directTls = bool(env, "server.ssl.enabled", false);
        if (directTls) {
            requirePresent(env, findings, "server.ssl.key-store", "ssl_key_store_missing");
            requirePresent(env, findings, "server.ssl.key-store-password", "ssl_key_store_password_missing");
            if (productionProfile && !bool(env, "security.force-https", false)) {
                findings.add(new Finding("security.force-https", "direct_tls_plain_http_not_redirected"));
            }
            if (productionProfile
                    && !"none".equals(norm(prop(env, "server.forward-headers-strategy", "none")))) {
                findings.add(new Finding("server.forward-headers-strategy", "direct_tls_forward_headers_enabled"));
            }
            return;
        }
        if (!productionProfile) return;

        if (!bool(env, "security.tls-offload.enabled", false)) {
            findings.add(new Finding("security.tls-offload.enabled", "production_tls_boundary_missing"));
            return;
        }
        if (!bool(env, "security.force-https", false)) {
            findings.add(new Finding("security.force-https", "tls_offload_https_not_enforced"));
        }

        String strategy = norm(prop(env, "server.forward-headers-strategy", "none"));
        if ("framework".equals(strategy)) {
            if (!isLoopbackAddress(prop(env, "server.address", ""))) {
                findings.add(new Finding("server.address", "tls_offload_backend_not_loopback"));
            }
            return;
        }
        if ("native".equals(strategy)) {
            if (!isLoopbackAddress(prop(env, "server.address", ""))) {
                findings.add(new Finding("server.address", "tls_offload_backend_not_loopback"));
            }
            String trustedProxies = prop(env, "server.tomcat.remoteip.internal-proxies", "");
            if (ConfigValueGuards.isMissing(trustedProxies)) {
                findings.add(new Finding("server.tomcat.remoteip.internal-proxies",
                        "tls_offload_trusted_proxies_missing"));
            } else if (!isExplicitLoopbackProxyPattern(trustedProxies)) {
                findings.add(new Finding("server.tomcat.remoteip.internal-proxies",
                        "tls_offload_trusted_proxies_unbounded"));
            }
            return;
        }
        findings.add(new Finding("server.forward-headers-strategy", "tls_offload_forward_headers_disabled"));
    }

    private static boolean isExplicitLoopbackProxyPattern(String expression) {
        String value = expression == null ? "" : expression.trim();
        return "127\\.0\\.0\\.1".equals(value)
                || "^127\\.0\\.0\\.1$".equals(value)
                || "::1".equals(value)
                || "^::1$".equals(value);
    }

    private static boolean isLoopbackAddress(String address) {
        String value = norm(address);
        return "127.0.0.1".equals(value)
                || "localhost".equals(value)
                || "::1".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value);
    }

    private static void checkRequiredSecrets(Environment env, List<Finding> findings) {
        requirePresent(env, findings, "spring.datasource.password", "db_password_missing");
        requirePresent(env, findings, "domain.allowlist.admin-token", "admin_token_missing");
        requirePresent(env, findings, "security.admin-secret", "admin_secret_missing");
        requirePresent(env, findings, "security.remember-me-key", "remember_me_key_missing");
        requirePresent(env, findings, "security.bootstrap-admin.password", "bootstrap_admin_password_missing");
        if (bool(env, "probe.search.enabled", false)) {
            requirePresent(env, findings, "probe.admin-token", "probe_admin_token_missing");
        }
    }

    private static void checkOnnx(Environment env, List<Finding> findings) {
        if (!bool(env, "onnx.enabled", false)
                && !bool(env, "zsys.onnx.enabled", false)
                && !bool(env, "abandonware.reranker.onnx.runtime-enabled", false)) {
            return;
        }
        List<String> paths = List.of(
                prop(env, "onnx.model.path.cross-encoder", ""),
                prop(env, "onnx.model-path", ""),
                prop(env, "abandonware.reranker.onnx.model-path", "")
        );
        boolean hasRealPath = paths.stream().anyMatch(RuntimeConfigGuard::usableOnnxModelPath);
        if (!hasRealPath) {
            findings.add(new Finding("onnx.model-path", "placeholder_or_too_small_model"));
        }
    }

    private static void checkBackgroundWorkloads(Environment env, List<Finding> findings) {
        if (bool(env, "train_idle.enabled", false)) {
            findings.add(new Finding("train_idle.enabled", "train_idle_enabled_in_strict_profile"));
        }
        if (bool(env, "autolearn.enabled", false)) {
            findings.add(new Finding("autolearn.enabled", "autolearn_enabled_in_strict_profile"));
        }
        if (bool(env, "uaw.autolearn.enabled", false)) {
            findings.add(new Finding("uaw.autolearn.enabled", "uaw_autolearn_enabled_in_strict_profile"));
        }
        if (bool(env, "local-llm.enabled", false)) {
            findings.add(new Finding("local-llm.enabled", "local_llm_enabled_in_strict_profile"));
        }
        if (bool(env, "local-llm.autostart", false)) {
            findings.add(new Finding("local-llm.autostart", "local_llm_autostart_enabled_in_strict_profile"));
        }
        if (bool(env, "selfask.enabled", false)) {
            findings.add(new Finding("selfask.enabled", "selfask_enabled_in_strict_profile"));
        }
        if (bool(env, "tavily.enabled", false)) {
            findings.add(new Finding("tavily.enabled", "tavily_enabled_in_strict_profile"));
        }
        if (bool(env, "soak.enabled", false)
                && bool(env, "soak.quick-runner.enabled", false)
                && bool(env, "soak.quick-runner.cli", false)
                && bool(env, "soak.quick-runner.exit-after-run", true)) {
            findings.add(new Finding("soak.quick-runner.exit-after-run",
                    "soak_quick_runner_exit_enabled_in_strict_profile"));
        }
    }

    private static void requirePresent(Environment env, List<Finding> findings, String property, String reason) {
        if (ConfigValueGuards.isMissing(prop(env, property, ""))) {
            findings.add(new Finding(property, reason));
        }
    }

    private static boolean missingOrPlaceholderPath(String value) {
        if (ConfigValueGuards.isMissing(value)) {
            return true;
        }
        String s = value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        return s.equals("/models/cross-encoder.onnx")
                || s.equals("file:/opt/models/cross-encoder.onnx")
                || s.equals("classpath:models/your-cross-encoder.onnx")
                || s.equals("models/your-cross-encoder.onnx")
                || s.endsWith("/models/your-cross-encoder.onnx")
                || s.endsWith("/your-cross-encoder.onnx")
                || s.contains("/placeholder/");
    }

    private static boolean usableOnnxModelPath(String value) {
        if (missingOrPlaceholderPath(value)) {
            return false;
        }
        String s = value.trim();
        String normalized = s.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("classpath:")) {
            return true;
        }
        try {
            Path path = normalized.startsWith("file:")
                    ? Path.of(URI.create(s))
                    : Path.of(s);
            return Files.isRegularFile(path) && Files.size(path) >= MIN_ONNX_MODEL_BYTES;
        } catch (Exception ignore) {
            traceSuppressed("runtimeConfig.onnxModelPath", ignore);
            return false;
        }
    }

    private static void traceSuppressed(String stage, Exception failure) {
        if (log.isDebugEnabled()) {
            log.debug("{} suppressed stage={} errorType={}",
                    PREFIX,
                    stage,
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
        }
    }

    private static boolean bool(Environment env, String property, boolean fallback) {
        String value = prop(env, property, "");
        if (value.isBlank()) {
            return fallback;
        }
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("y");
    }

    private static String prop(Environment env, String property, String fallback) {
        String value = env == null ? null : env.getProperty(property);
        return value == null ? fallback : value.trim();
    }

    private static Set<String> profiles(Environment env) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (env != null) {
            Arrays.stream(env.getActiveProfiles()).map(RuntimeConfigGuard::norm).filter(s -> !s.isBlank()).forEach(out::add);
            if (out.isEmpty()) {
                Arrays.stream(env.getDefaultProfiles()).map(RuntimeConfigGuard::norm).filter(s -> !s.isBlank()).forEach(out::add);
            }
        }
        if (out.isEmpty()) {
            out.add("default");
        }
        return out;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isProductionProfile(String profile) {
        return profile.equals("prod")
                || profile.equals("production")
                || profile.equals("live")
                || profile.startsWith("prod-")
                || profile.startsWith("production-")
                || profile.startsWith("live-");
    }

    private static String profileLabel(Set<String> profiles) {
        return String.join(",", profiles);
    }

    private static Set<String> csv(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String token : raw.split(",")) {
            String t = norm(token);
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String summarize(List<Finding> findings) {
        return findings.stream()
                .map(f -> f.property() + ":" + f.reason() + ":" + f.classification())
                .distinct()
                .limit(12)
                .reduce((a, b) -> a + "," + b)
                .orElse("none");
    }

    private static String classify(String reason) {
        String r = norm(reason);
        if (r.contains("actuator") || r.contains("env_values") || r.contains("httptrace")) {
            return "actuator-exposure";
        }
        if (r.contains("ssl") || r.contains("tls") || r.contains("transport")) {
            return "ssl-risk";
        }
        if (r.contains("missing") || r.contains("secret") || r.contains("password") || r.contains("token")
                || r.contains("key_")) {
            return "secret-required";
        }
        if (r.contains("debug") || r.contains("dump")) {
            return "debug-exposure";
        }
        if (r.contains("cors")) {
            return "cors-risk";
        }
        if (r.contains("train_idle") || r.contains("autolearn") || r.contains("selfask") || r.contains("tavily")
                || r.contains("soak_quick_runner")) {
            return "background-workload";
        }
        if (r.contains("onnx") || r.contains("local_llm")) {
            return "local-model-risk";
        }
        return "runtime-config-risk";
    }

    record Evaluation(boolean enabled, boolean strict, String profileLabel, List<Finding> findings) {
    }

    record Finding(String property, String reason, String classification) {
        Finding(String property, String reason) {
            this(property, reason, RuntimeConfigGuard.classify(reason));
        }
    }
}
