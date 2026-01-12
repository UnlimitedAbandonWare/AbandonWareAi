package com.example.lms.orchestration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stage policy: OPTIONAL/CRITICAL classification + per-mode enablement.
 *
 * <p>Bound from YAML via {@link ConfigurationProperties}.
 */
@Data
@ConfigurationProperties(prefix = "orchestration.stage-policy")
public class StagePolicyProperties {

    private boolean enabled = true;

    private double defaultOptionalIrregularityDelta = 0.12;
    private double defaultCriticalIrregularityDelta = 0.22;

    private List<Rule> rules = new ArrayList<>();

    public enum Importance {
        OPTIONAL,
        CRITICAL
    }

    @Data
    public static class Rule {
        private String id;
        private String pattern; // glob-like
        private Importance importance = Importance.OPTIONAL;
        private List<String> enableIn = new ArrayList<>();
        private Boolean enabled;
        private Double irregularityDelta;

        private transient Pattern compiled;

        boolean matches(String stageKey) {
            if (pattern == null || pattern.isBlank() || stageKey == null) {
                return false;
            }
            if (pattern.equals(stageKey)) {
                return true;
            }
            return compiledPattern().matcher(stageKey).matches();
        }

        boolean isEnabledInMode(String modeLabel) {
            if (enableIn == null || enableIn.isEmpty() || modeLabel == null || modeLabel.isBlank()) {
                return true;
            }
            String m = modeLabel.trim().toUpperCase(Locale.ROOT);
            for (String v : enableIn) {
                if (v == null) {
                    continue;
                }
                if (m.equals(v.trim().toUpperCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        private Pattern compiledPattern() {
            if (compiled != null) {
                return compiled;
            }
            compiled = compileGlob(pattern);
            return compiled;
        }
    }

    public record Decision(boolean enabled, Importance importance, Double irregularityDelta, String ruleId) {
    }

    public Decision decide(String stageKey, String modeLabel, boolean defaultEnabled) {
        Rule rule = matchRule(stageKey);
        if (rule == null) {
            return new Decision(defaultEnabled, Importance.OPTIONAL, null, null);
        }
        boolean enabledByRule = rule.enabled == null ? true : Boolean.TRUE.equals(rule.enabled);
        boolean enabledInMode = rule.isEnabledInMode(modeLabel);
        boolean enabled = enabledByRule && enabledInMode;
        return new Decision(
                enabled,
                rule.importance == null ? Importance.OPTIONAL : rule.importance,
                rule.irregularityDelta,
                rule.id
        );
    }

    public boolean isStageEnabled(String stageKey, String modeLabel, boolean defaultEnabled) {
        if (!enabled) {
            return defaultEnabled;
        }
        return decide(stageKey, modeLabel, defaultEnabled).enabled();
    }

    public Importance importanceOf(String stageKey, Importance fallback) {
        if (!enabled) {
            return fallback;
        }
        Rule rule = matchRule(stageKey);
        if (rule == null || rule.importance == null) {
            return fallback;
        }
        return rule.importance;
    }

    public double irregularityDeltaFor(String stageKey, Importance fallbackImportance) {
        if (!enabled) {
            return fallbackImportance == Importance.CRITICAL
                    ? defaultCriticalIrregularityDelta
                    : defaultOptionalIrregularityDelta;
        }

        Rule rule = matchRule(stageKey);
        Importance imp = (rule != null && rule.importance != null) ? rule.importance : fallbackImportance;

        if (rule != null && rule.irregularityDelta != null) {
            return rule.irregularityDelta;
        }

        return imp == Importance.CRITICAL
                ? defaultCriticalIrregularityDelta
                : defaultOptionalIrregularityDelta;
    }

    private Rule matchRule(String stageKey) {
        if (rules == null || rules.isEmpty() || stageKey == null || stageKey.isBlank()) {
            return null;
        }
        for (Rule r : rules) {
            if (r == null) {
                continue;
            }
            if (r.matches(stageKey)) {
                return r;
            }
        }
        return null;
    }

    private static Pattern compileGlob(String glob) {
        if (glob == null) {
            return Pattern.compile("^$");
        }
        StringBuilder sb = new StringBuilder();
        sb.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.' -> sb.append("\\.");
                case '\\' -> sb.append("\\\\");
                case '(' -> sb.append("\\(");
                case ')' -> sb.append("\\)");
                case '[' -> sb.append("\\[");
                case ']' -> sb.append("\\]");
                case '{' -> sb.append("\\{");
                case '}' -> sb.append("\\}");
                case '+' -> sb.append("\\+");
                case '^' -> sb.append("\\^");
                case '$' -> sb.append("\\$");
                case '|' -> sb.append("\\|");
                default -> sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }
}
