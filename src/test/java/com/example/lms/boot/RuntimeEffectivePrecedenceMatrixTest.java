package com.example.lms.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeEffectivePrecedenceMatrixTest {
    @TempDir Path isolated;
    private static final List<String> FILES = List.of("application.properties", "application.yml",
            "application-dev.yml", "application-local.yml", "application-llm.yaml");
    private static final List<String> KEYS = List.of(
            "spring.config.import",
            "spring.datasource.driver-class-name",
            "spring.datasource.hikari.connection-init-sql",
            "spring.datasource.hikari.connection-timeout",
            "spring.datasource.hikari.idle-timeout",
            "spring.datasource.hikari.maximum-pool-size",
            "spring.datasource.hikari.minimum-idle",
            "spring.datasource.password",
            "spring.datasource.url",
            "spring.datasource.username",
            "server.port",
            "server.ssl.enabled",
            "server.ssl.key-password",
            "server.ssl.key-store",
            "server.ssl.key-store-password",
            "server.ssl.key-store-type",
            "management.endpoints.web.exposure.include",
            "management.endpoint.env.show-values",
            "management.endpoint.httptrace.enabled",
            "naver.keys",
            "naver.filters.enable-domain-filter",
            "naver.filters.domain-policy",
            "naver.filters.keyword-min-hits",
            "naver.search.web-top-k",
            "naver.search.timeout-ms",
            "fallback.enabled",
            "selfask.enabled",
            "probe.search.enabled",
            "probe.admin-token",
            "llm.fast.timeout-seconds",
            "onnx.enabled",
            "local-llm.enabled",
            "local-llm.base-url",
            "local-llm.autostart",
            "gemini.api-key",
            "tavily.enabled",
            "nova.orch.evidence-list.trace-injection.enabled",
            "uaw.autolearn.min-evidence-count",
            "energy.w.rel",
            "energy.w.auth",
            "energy.w.rec",
            "energy.w.red",
            "energy.w.ctr");
    private static final Set<String> ENV = Set.of(
            "APP_CONFIG_IMPORT",
            "LMS_DB_DRIVER",
            "LMS_DB_CONNECTION_INIT_SQL",
            "LMS_DB_HIKARI_CONNECTION_TIMEOUT",
            "LMS_DB_HIKARI_IDLE_TIMEOUT",
            "LMS_DB_HIKARI_MAX_POOL_SIZE",
            "LMS_DB_HIKARI_MIN_IDLE",
            "LMS_DB_PASSWORD",
            "LMS_DB_URL",
            "LMS_DB_USERNAME",
            "SERVER_PORT",
            "SERVER_SSL_ENABLED",
            "SERVER_SSL_KEY_PASSWORD",
            "SERVER_SSL_KEY_STORE",
            "SERVER_SSL_KEY_STORE_PASSWORD",
            "SERVER_SSL_KEY_STORE_TYPE",
            "NAVER_KEYS",
            "SELFASK_ENABLED",
            "PROBE_SEARCH_ENABLED",
            "DOMAIN_ALLOWLIST_ADMIN_TOKEN",
            "PROBE_ADMIN_TOKEN",
            "ONNX_ENABLED",
            "LOCAL_LLM_ENABLED",
            "LOCAL_LLM_BASE_URL",
            "OPENAI_COMPAT_BASE_URL",
            "LOCAL_LLM_AUTOSTART",
            "GEMINI_API_KEY",
            "TAVILY_ENABLED",
            "NOVA_EVIDENCE_LIST_TRACE_INJECTION_ENABLED",
            "UAW_AUTOLEARN_MIN_EVIDENCE_COUNT");
    private static final Set<String> NONE = Set.of(
            "management.endpoints.web.exposure.include",
            "management.endpoint.env.show-values",
            "management.endpoint.httptrace.enabled",
            "naver.filters.enable-domain-filter",
            "naver.filters.domain-policy",
            "naver.filters.keyword-min-hits",
            "naver.search.web-top-k",
            "naver.search.timeout-ms",
            "fallback.enabled",
            "llm.fast.timeout-seconds",
            "energy.w.rel",
            "energy.w.auth",
            "energy.w.rec",
            "energy.w.red",
            "energy.w.ctr");
    private static final String NO_SYSTEM_NAMES = "none_declared_in_anchored_files";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)");
    private static final String SYNTH_ENV = "tbl08SyntheticEnvironment";
    private static final String SYNTH_SYS = "tbl08SyntheticSystemProperties";

    @Test
    void closedInventoryMatchesCanonicalLiteralsWithoutExposingValues() throws Exception {
        String directive = Files.readString(Path.of("agent-prompts/gpt_pro_demo1_source_audit_100.md"));
        String clause = directive.substring(directive.indexOf("### [TBL-08]"),
                directive.indexOf("### [TBL-09]"));
        assertEquals(43, KEYS.size());
        assertEquals(30, ENV.size());
        assertEquals(15, NONE.size());
        assertTrue(clause.contains(NO_SYSTEM_NAMES), "missing system-property sentinel");
        String quote = String.valueOf((char) 96);
        for (String key : KEYS) assertTrue(clause.contains(quote + key + quote), "canonical key " + key);
        for (String name : ENV) assertTrue(clause.contains(quote + name + quote), "canonical env " + name);
        Map<String, Map<String, Object>> parsed = parseFiles();
        Set<String> seenEnv = new TreeSet<>(), absent = new TreeSet<>();
        for (String key : KEYS) {
            boolean present = false;
            Set<String> placeholders = new TreeSet<>();
            for (var source : parsed.values()) {
                if (member(source, key) != null) present = true;
                for (var entry : source.entrySet()) {
                    if (!entry.getKey().equals(key) && !entry.getKey().startsWith(key + "[")) continue;
                    var matcher = PLACEHOLDER.matcher(String.valueOf(entry.getValue()));
                    while (matcher.find()) {
                        String name = matcher.group(1);
                        if (name.matches("[A-Z][A-Z0-9_]*")) placeholders.add(name);
                    }
                }
            }
            assertTrue(present, "closed key absent: " + key);
            seenEnv.addAll(placeholders);
            if (placeholders.isEmpty()) absent.add(key);
        }
        assertEquals(ENV, seenEnv, "declared environment-name set");
        assertEquals(NONE, absent, "declared no-placeholder key set");
        System.out.println("TBL08_INVENTORY files=5 keys=43 environments=30 none=15 system=none_declared_in_anchored_files");
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({"default,base", "dev,base", "local,base", "llm,base",
            "default,environment", "dev,environment", "local,environment", "llm,environment",
            "default,system", "dev,system", "local,system", "llm,system"})
    void effectiveOwnersFollowTheClosedProfileAndSyntheticOverrideContract(String profile, String mode)
            throws Exception {
        Map<String, Map<String, Object>> parsed = parseFiles();
        for (String file : FILES) Files.copy(Path.of("main/resources", file), isolated.resolve(file));
        Map<String, Object> syntheticEnv = new LinkedHashMap<>();
        // Empty the import through its declared placeholder, preserving its file source owner.
        syntheticEnv.put("APP_CONFIG_IMPORT", "");
        if (!mode.equals("base")) for (String name : ENV)
            syntheticEnv.put(name, name.equals("APP_CONFIG_IMPORT") ? "" : "fixture_" + name);
        Map<String, Object> syntheticSystem = new LinkedHashMap<>();
        if (mode.equals("system")) for (String key : KEYS)
            syntheticSystem.put(key, key.equals("spring.config.import") ? "" : "fixture_system");
        List<String> precedence = List.of("application-" + (profile.equals("default") ? "local" : profile)
                + (profile.equals("llm") ? ".yaml" : ".yml"), "application.properties", "application.yml");
        List<String> controls = new ArrayList<>();
        controls.add("spring.config.location=" + isolated.toUri());
        if (!profile.equals("default")) controls.add("spring.profiles.active=" + profile);
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.addFirst(new SystemEnvironmentPropertySource(SYNTH_ENV, syntheticEnv));
                    sources.addFirst(new MapPropertySource(SYNTH_SYS, syntheticSystem));
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withPropertyValues(controls.toArray(String[]::new))
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null, "isolated ConfigData context startup failed");
                    ConfigurableEnvironment actual = context.getEnvironment();
                    assertNull(actual.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME));
                    assertNull(actual.getPropertySources().get(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME));
                    if (profile.equals("default")) {
                        assertArrayEquals(new String[0], actual.getActiveProfiles());
                        assertArrayEquals(new String[]{"local"}, actual.getDefaultProfiles());
                    } else assertArrayEquals(new String[]{profile}, actual.getActiveProfiles());
                    Set<String> loaded = new TreeSet<>();
                    for (PropertySource<?> source : actual.getPropertySources()) {
                        if (!source.getName().startsWith("Config resource")) continue;
                        String file = fileOwner(source.getName());
                        assertNotEquals("unknown", file, "source outside anchored files");
                        loaded.add(file);
                    }
                    assertEquals(new TreeSet<>(precedence), loaded, "exact files loaded");
                    for (String key : KEYS) {
                        String expected = "absent";
                        String envName = key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
                        if (syntheticSystem.containsKey(key)) expected = SYNTH_SYS;
                        else if (syntheticEnv.containsKey(envName)) expected = SYNTH_ENV;
                        else for (String file : precedence)
                            if (member(parsed.get(file), key) != null) { expected = file; break; }
                        PropertySource<?> owner = findOwner(actual, key);
                        String observed = owner == null ? "absent" : owner.getName().equals(SYNTH_SYS)
                                ? SYNTH_SYS : owner.getName().equals(SYNTH_ENV) ? SYNTH_ENV : fileOwner(owner.getName());
                        assertEquals(expected, observed, "owner for " + profile + "/" + mode + "/" + key);
                        if (owner != null) {
                            String name = owner.getProperty(key) != null ? key : key + "[0]";
                            String resolved = actual.getProperty(name);
                            assertTrue(resolved != null, "effective member unresolved: " + key);
                            if (expected.equals(SYNTH_SYS))
                                assertTrue(Objects.equals(syntheticSystem.get(key), resolved), "system value mismatch: " + key);
                            if (expected.equals(SYNTH_ENV))
                                assertTrue(Objects.equals(syntheticEnv.get(envName), resolved), "environment value mismatch: " + key);
                        }
                        System.out.println("TBL08_OWNER profile=" + profile + " mode=" + mode
                                + " key=" + key + " owner=" + observed);
                    }
                    assertEquals(0, context.getBeanNamesForType(javax.sql.DataSource.class).length);
                    System.out.println("TBL08_PROFILE profile=" + profile + " mode=" + mode
                            + " keys=43 loadedFiles=3 applicationStarted=false externalImports=0");
                });
    }

    private static String member(Map<String, Object> map, String key) {
        if (map == null) return null;
        if (map.containsKey(key) && map.get(key) != null) return key;
        if (map.containsKey(key + "[0]")) return key + "[0]";
        return null;
    }

    private static PropertySource<?> findOwner(ConfigurableEnvironment env, String key) {
        for (PropertySource<?> source : env.getPropertySources()) {
            String name = source.getName();
            if (!name.equals(SYNTH_SYS) && !name.equals(SYNTH_ENV) && !name.startsWith("Config resource")) continue;
            if (source.getProperty(key) != null || source.getProperty(key + "[0]") != null) return source;
        }
        return null;
    }

    private static String fileOwner(String sourceName) {
        sourceName = sourceName.replace('\\', '/');
        for (String file : FILES) if (sourceName.contains("/" + file + "]") || sourceName.contains("/" + file + "'"))
            return file;
        return "unknown";
    }

    private static Map<String, Map<String, Object>> parseFiles() throws Exception {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String file : FILES) {
            var resource = new FileSystemResource(Path.of("main/resources", file));
            List<PropertySource<?>> documents = file.endsWith(".properties")
                    ? new PropertiesPropertySourceLoader().load(file, resource)
                    : new YamlPropertySourceLoader().load(file, resource);
            assertEquals(1, documents.size(), "document count for " + file);
            Map<String, Object> values = new LinkedHashMap<>();
            for (var document : documents) {
                assertTrue(document instanceof EnumerablePropertySource<?>, "enumerable source required");
                for (String key : ((EnumerablePropertySource<?>) document).getPropertyNames())
                    values.put(key, document.getProperty(key));
            }
            result.put(file, values);
        }
        return result;
    }
}

