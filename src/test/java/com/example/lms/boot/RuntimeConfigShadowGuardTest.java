package com.example.lms.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigShadowGuardTest {

    @Test
    void rootApplicationPropertiesDoesNotOwnP0RuntimeBaseline() throws IOException {
        List<String> active = activeLines(Path.of("main/resources/application.properties"));

        assertNoActive(active, "^spring\\.config\\.import\\s*=");
        assertNoActive(active, "^spring\\.datasource\\.");
        assertNoActive(active, "^server\\.port\\s*=");
        assertNoActive(active, "^server\\.ssl\\.");
        assertNoActive(active, "^management\\.endpoints\\.web\\.exposure\\.include\\s*=");
        assertNoActive(active, "^management\\.endpoint\\.env\\.show-values\\s*=");
        assertNoActive(active, "^management\\.endpoint\\.httptrace\\.enabled\\s*=");
        assertNoActive(active, "^naver\\.keys\\s*=");
        assertNoActive(active, "^fallback\\.enabled\\s*=");
        assertNoActive(active, "^selfask\\.enabled\\s*=");
        assertNoActive(active, "^probe\\.search\\.enabled\\s*=");
        assertNoActive(active, "^probe\\.admin-token\\s*=");
        assertNoActive(active, "^naver\\.filters\\.(enable-domain-filter|domain-policy|keyword-min-hits)\\s*=");
        assertNoActive(active, "^naver\\.search\\.(web-top-k|timeout-ms)\\s*=");
        assertNoActive(active, "^llm\\.fast\\.timeout-seconds\\s*=");
        assertNoActive(active, "^onnx\\.enabled\\s*=");
        assertNoActive(active, "^local-llm\\.enabled\\s*=.*true");
    }

    @Test
    void rootApplicationPropertiesAvoidsMutableSchemaAndVerboseDefaults() throws IOException {
        List<String> active = activeLines(Path.of("main/resources/application.properties"));

        assertNoActive(active, "^spring\\.jpa\\.hibernate\\.ddl-auto\\s*=\\s*update\\s*$");
        assertNoActive(active, "^spring\\.jpa\\.show-sql\\s*=\\s*true\\s*$");
        assertNoActive(active, "^tavily\\.enabled\\s*=\\s*true\\s*$");
        assertNoActive(active, "^logging\\.level\\..*=\\s*(DEBUG|TRACE)\\s*$");
        assertNoActive(active, "^naver\\.search\\.debug(-json)?\\s*=\\s*true\\s*$");
        assertNoActive(active, "^probe\\.search\\.console-trace\\.enabled\\s*=\\s*true\\s*$");
        assertNoActive(active, "^rgb\\.moe\\.debug\\.enabled\\s*=\\s*true\\s*$");
    }

    @Test
    void rootApplicationPropertiesKeepsLocalAndExternalFallbacksSeparated() throws IOException {
        String props = Files.readString(Path.of("main/resources/application.properties"))
                .replace("\r\n", "\n");
        List<String> active = activeLines(Path.of("main/resources/application.properties"));

        assertTrue(active.contains("gemini.api-key=${GEMINI_API_KEY:}"));
        assertNoActive(active, "^gemini\\.api-key\\s*=\\s*$");
        assertFalse(props.contains("local-llm.base-url=${OPENAI_COMPAT_BASE_URL:https://api.groq.com/openai/v1}"),
                "local-llm must not default to an external Groq endpoint");
        assertTrue(active.contains("local-llm.base-url=${LOCAL_LLM_BASE_URL:${OPENAI_COMPAT_BASE_URL:http://localhost:11434/v1}}"));
        assertFalse(Pattern.compile("(?m)^energy\\.w\\.rel\\s*=.*energy\\.w\\.auth").matcher(props).find(),
                "energy weights must not be collapsed into one malformed property line");
        assertTrue(active.contains("energy.w.rel=0.6"));
        assertTrue(active.contains("energy.w.auth=0.2"));
        assertTrue(active.contains("energy.w.rec=0.1"));
        assertTrue(active.contains("energy.w.red=0.6"));
        assertTrue(active.contains("energy.w.ctr=0.8"));
    }

    @Test
    void rootApplicationYmlDoesNotImportBundledSecrets() throws IOException {
        String appYml = Files.readString(Path.of("main/resources/application.yml"));

        assertFalse(appYml.contains("optional:application-secrets.yml"),
                "application.yml must not import bundled application-secrets.yml by default");
        assertTrue(appYml.contains("APP_CONFIG_IMPORT"),
                "external secret imports should be opt-in through APP_CONFIG_IMPORT or Spring additional location");
    }

    @Test
    void rootApplicationYmlKeepsRiskyProbesAndAutolearnThresholdOptIn() throws IOException {
        String appYml = Files.readString(Path.of("main/resources/application.yml"));

        assertTrue(appYml.contains("enabled: ${PROBE_SEARCH_ENABLED:false}"));
        assertTrue(appYml.contains("enabled: ${PROBE_SOAK_ENABLED:false}"));
        assertTrue(appYml.contains("enabled: ${GPT_SEARCH_SOAK_ENABLED:false}"));
        assertTrue(appYml.contains("min-evidence-count: ${UAW_AUTOLEARN_MIN_EVIDENCE_COUNT:3}"));
    }

    @Test
    void evidenceTraceInjectionIsOptInGloballyButEnabledForLocalDiagnostics() throws IOException {
        String appYml = Files.readString(Path.of("main/resources/application.yml"))
                .replace("\r\n", "\n");
        String devYml = Files.readString(Path.of("main/resources/application-dev.yml"))
                .replace("\r\n", "\n");
        String localYml = Files.readString(Path.of("main/resources/application-local.yml"))
                .replace("\r\n", "\n");

        assertTrue(appYml.contains("evidence-list:\n      trace-injection:\n        enabled: ${NOVA_EVIDENCE_LIST_TRACE_INJECTION_ENABLED:false}"),
                "root application.yml must keep trace injection env/false opt-in");
        assertFalse(appYml.contains("trace-injection:\n        enabled: true"),
                "root application.yml must not force user-visible diagnostics on every profile");
        assertTrue(devYml.contains("evidence-list:\n      trace-injection:\n        enabled: true"),
                "dev profile should enable trace injection for diagnostics");
        assertTrue(localYml.contains("evidence-list:\n      trace-injection:\n        enabled: true"),
                "local profile should enable trace injection for diagnostics");
    }

    @Test
    void llmOverlayRuntimeActivationIsOptIn() throws IOException {
        String llmYaml = Files.readString(Path.of("main/resources/application-llm.yaml"))
                .replace("\r\n", "\n");

        assertTrue(llmYaml.contains("local-llm:\n  enabled: ${LOCAL_LLM_ENABLED:false}\n"
                + "  autostart: ${LOCAL_LLM_AUTOSTART:false}"));
        assertTrue(llmYaml.contains("tavily.enabled: ${TAVILY_ENABLED:false}"));
        assertTrue(llmYaml.contains("selfask.enabled: ${SELFASK_ENABLED:false}"));
    }

    @Test
    void appDefaultResourcesAreOptInForOperationalRisk() throws IOException {
        String appYml = Files.readString(Path.of("app/src/main/resources/application.yml"));
        String appYaml = Files.readString(Path.of("app/src/main/resources/application.yaml"));
        String appProps = Files.readString(Path.of("app/src/main/resources/application.properties"));
        String combined = appYml + "\n" + appYaml + "\n" + appProps;

        assertFalse(Pattern.compile("(?m)^\\s*active\\s*:").matcher(appYml).find(),
                "app application.yml must not activate a profile");
        assertFalse(Pattern.compile("(?m)^spring\\.config\\.import\\s*[:=]").matcher(combined).find(),
                "app defaults must not import extra config");
        assertFalse(Pattern.compile("(?m)^\\s*api-key\\s*:\\s*dummy\\s*$").matcher(combined).find(),
                "app defaults must not contain dummy LLM credentials");
        assertFalse(riskyYamlEnabled(appYml),
                "app application.yml ONNX/local-llm/autolearn defaults must be opt-in/env based");
        assertFalse(riskyYamlEnabled(appYaml),
                "app application.yaml ONNX/local-llm/autolearn defaults must be opt-in/env based");
        assertTrue(appProps.contains("probe.search.enabled=${PROBE_SEARCH_ENABLED:false}"));
        assertTrue(appProps.contains("local-llm.enabled=${LOCAL_LLM_ENABLED:false}"));
        assertTrue(appProps.contains("onnx.enabled=${ONNX_ENABLED:false}"));
    }

    @Test
    void packagedUltraAndLearningProfilesUseSafeDefaults() throws IOException {
        List<String> base = activeLines(Path.of("main/resources/application.yml"));
        List<String> ultra = activeLines(Path.of("main/resources/application-ultra.properties"));
        List<String> learning = activeLines(Path.of("main/resources/application-learning.yml"));
        String rootBuild = Files.readString(Path.of("build.gradle.kts"));
        String appBuild = Files.readString(Path.of("app/build.gradle.kts"));

        assertNoActive(base, "^wiretap\\s*:\\s*true(?:\\s*#.*)?$");
        assertTrue(ultra.contains("spring.reactor.netty.http.server.wiretap=false"));
        assertTrue(ultra.contains("spring.reactor.netty.http.client.wiretap=false"));
        assertTrue(rootBuild.contains("srcDirs(\"main/resources\")"),
                "root module must own packaged runtime configuration");
        assertTrue(appBuild.contains(
                        "exclude(\"application*.yml\", \"application*.yaml\", \"application*.properties\")"),
                ":app must exclude duplicate application configuration resources");

        assertAll(
                () -> assertTrue(ultra.contains(
                        "server.error.include-stacktrace=${ULTRA_ERROR_INCLUDE_STACKTRACE:never}")),
                () -> assertTrue(ultra.contains(
                        "logging.level.org.hibernate.type.descriptor.sql.BasicBinder=${ULTRA_HIBERNATE_BINDER_LOG_LEVEL:WARN}")),
                () -> assertTrue(ultra.contains(
                        "logging.level.org.springframework.transaction=${ULTRA_TRANSACTION_LOG_LEVEL:WARN}")),
                () -> assertTrue(ultra.contains(
                        "logging.level.dev.langchain4j=${ULTRA_LANGCHAIN4J_LOG_LEVEL:WARN}")),
                () -> assertTrue(learning.contains(
                        "ddl-auto: ${LEARNING_JPA_DDL_AUTO:validate}")),
                () -> assertNoActive(ultra,
                        "^server\\.error\\.include-stacktrace\\s*=\\s*always(?:\\s*[#!].*)?$"),
                () -> assertNoActive(ultra,
                        "^logging\\.level\\.org\\.hibernate\\.type\\.descriptor\\.sql\\.BasicBinder"
                                + "\\s*=\\s*TRACE(?:\\s*[#!].*)?$"),
                () -> assertNoActive(ultra,
                        "^logging\\.level\\.org\\.springframework\\.transaction"
                                + "\\s*=\\s*TRACE(?:\\s*[#!].*)?$"),
                () -> assertNoActive(ultra,
                        "^logging\\.level\\.dev\\.langchain4j\\s*=\\s*TRACE(?:\\s*[#!].*)?$"),
                () -> assertNoActive(learning,
                        "^ddl-auto\\s*:\\s*create-drop(?:\\s*#.*)?$")
        );
    }

    private static List<String> activeLines(Path path) throws IOException {
        return Files.readAllLines(path).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    private static void assertNoActive(List<String> lines, String regex) {
        Pattern pattern = Pattern.compile(regex);
        assertFalse(lines.stream().anyMatch(line -> pattern.matcher(line).find()), regex);
    }

    private static boolean riskyYamlEnabled(String yaml) {
        return sectionHasEnabledTrue(yaml, "onnx")
                || sectionHasEnabledTrue(yaml, "local-llm")
                || sectionHasEnabledTrue(yaml, "train_idle")
                || sectionHasEnabledTrue(yaml, "autolearn")
                || nestedSectionHasEnabledTrue(yaml, "zsys", "onnx");
    }

    private static boolean sectionHasEnabledTrue(String yaml, String section) {
        boolean inSection = false;
        for (String line : yaml.split("\\R")) {
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                inSection = line.trim().equals(section + ":");
                continue;
            }
            if (inSection && line.trim().equals("enabled: true")) {
                return true;
            }
        }
        return false;
    }

    private static boolean nestedSectionHasEnabledTrue(String yaml, String parent, String child) {
        boolean inParent = false;
        boolean inChild = false;
        for (String line : yaml.split("\\R")) {
            String trimmed = line.trim();
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                inParent = trimmed.equals(parent + ":");
                inChild = false;
                continue;
            }
            if (inParent && line.startsWith("  ") && !line.startsWith("    ")) {
                inChild = trimmed.equals(child + ":");
                continue;
            }
            if (inParent && inChild && trimmed.equals("enabled: true")) {
                return true;
            }
        }
        return false;
    }
}
