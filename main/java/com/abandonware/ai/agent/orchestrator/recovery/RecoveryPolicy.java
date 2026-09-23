package com.abandonware.ai.agent.orchestrator.recovery;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RecoveryPolicy {
    private static final Logger log = LoggerFactory.getLogger(RecoveryPolicy.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Map<FailureClass, RecoveryAction> actionMap = new EnumMap<>(FailureClass.class);
    private final Map<String, String> degradeMap = new LinkedHashMap<>();
    private int maxRounds = 2;
    private int minCitations = 3;
    private int budgetSoftMs = 500;

    public RecoveryPolicy() {
        installDefaults();
        apply(loadResourceRoot());
    }

    private RecoveryPolicy(Map<String, Object> root) {
        installDefaults();
        apply(root);
    }

    public static RecoveryPolicy load() {
        return new RecoveryPolicy();
    }

    public static RecoveryPolicy fromYaml(String yaml) {
        if (yaml == null) return new RecoveryPolicy(Map.of());
        return fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    public static RecoveryPolicy fromYaml(InputStream in) {
        try {
            return new RecoveryPolicy(readRoot(in));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid recovery policy YAML", e);
        }
    }

    public int maxRounds() {
        return maxRounds;
    }

    public int minCitations() {
        return minCitations;
    }

    public int budgetSoftMs() {
        return budgetSoftMs;
    }

    public RecoveryAction resolve(FailureClass failureClass) {
        return actionMap.getOrDefault(failureClass, RecoveryAction.ESCALATE);
    }

    public String degrade(String currentFlow) {
        return degradeMap.getOrDefault(currentFlow, "safe_autorun.v1");
    }

    private void installDefaults() {
        actionMap.put(FailureClass.TRANSIENT, RecoveryAction.BACKOFF);
        actionMap.put(FailureClass.LOGIC, RecoveryAction.DEGRADE);
        actionMap.put(FailureClass.DATA, RecoveryAction.FALLBACK);
        actionMap.put(FailureClass.POLICY, RecoveryAction.ESCALATE);
        actionMap.put(FailureClass.BUDGET, RecoveryAction.DEGRADE);
        actionMap.put(FailureClass.UNKNOWN, RecoveryAction.ESCALATE);

        degradeMap.put("hypernova.v2", "safe_autorun.v1");
        degradeMap.put("brave.v1", "safe_autorun.v1");
        degradeMap.put("recency_first.v1", "default.v1");
        degradeMap.put("rulebreak.v1", "default.v1");
    }

    @SuppressWarnings("unchecked")
    private void apply(Map<String, Object> root) {
        if (root == null || root.isEmpty()) return;

        Object recovery = root.get("recovery");
        if (recovery instanceof Map<?, ?> recoveryMap) {
            maxRounds = positiveInt(first(recoveryMap, "max-rounds", "maxRounds"), maxRounds);
            minCitations = positiveInt(first(recoveryMap, "min-citations", "minCitations"), minCitations);
            budgetSoftMs = positiveInt(first(recoveryMap, "budget-soft-ms", "budgetSoftMs"), budgetSoftMs);
        }

        Object map = root.get("map");
        if (map instanceof Map<?, ?> actionEntries) {
            for (Map.Entry<?, ?> entry : actionEntries.entrySet()) {
                String key = stringValue(entry.getKey());
                String value = stringValue(entry.getValue());
                if (key.isBlank() || value.isBlank()) continue;
                try {
                    actionMap.put(
                            FailureClass.valueOf(key.toUpperCase(Locale.ROOT)),
                            RecoveryAction.valueOf(value.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    log.warn("[AWX2AF2][yaml] ignored unknown recovery policy map entry: {}", key);
                }
            }
        }

        Object degrade = root.get("degrade-map");
        if (degrade instanceof Map<?, ?> degradeEntries) {
            for (Map.Entry<?, ?> entry : degradeEntries.entrySet()) {
                String key = stringValue(entry.getKey());
                String value = stringValue(entry.getValue());
                if (!key.isBlank() && !value.isBlank()) {
                    degradeMap.put(key, value);
                }
            }
        }
    }

    private static Map<String, Object> loadResourceRoot() {
        try (InputStream in = RecoveryPolicy.class.getClassLoader()
                .getResourceAsStream("policies/recovery-policy.yaml")) {
            return readRoot(in);
        } catch (Exception e) {
            log.warn("[AWX2AF2][yaml] recovery-policy.yaml unavailable or invalid; using safe defaults: {}",
                    e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static Map<String, Object> readRoot(InputStream in) throws java.io.IOException {
        if (in == null) return Map.of();
        Map<String, Object> root = YAML.readValue(in, MAP_TYPE);
        return root == null ? Map.of() : root;
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) return map.get(key);
        }
        return null;
    }

    private static int positiveInt(Object value, int fallback) {
        int parsed = intValue(value, fallback, "positiveInt");
        return parsed <= 0 ? fallback : parsed;
    }

    private static int intValue(Object value, int fallback, String stage) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof CharSequence s) {
            try {
                return Integer.parseInt(s.toString().trim());
            } catch (NumberFormatException ignored) {
                traceSuppressed(stage, s, ignored);
                return fallback;
            }
        }
        return fallback;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void traceSuppressed(String stage, CharSequence value, Throwable error) {
        String raw = value == null ? null : value.toString();
        TraceStore.put("agent.recovery.policy.suppressed", true);
        TraceStore.put("agent.recovery.policy.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.recovery.policy.suppressed.errorType", "invalid_number");
        TraceStore.put("agent.recovery.policy.suppressed.valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put("agent.recovery.policy.suppressed.valueLength", raw == null ? 0 : raw.length());
    }
}
