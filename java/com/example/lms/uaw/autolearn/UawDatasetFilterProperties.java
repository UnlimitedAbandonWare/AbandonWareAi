package com.example.lms.uaw.autolearn;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dataset training-data filter rules.
 *
 * <p>This configuration is intentionally expressive so operations can tune exclusions
 * without code deploys.
 */
@Data
@ConfigurationProperties(prefix = "uaw.autolearn.dataset-filter")
public class UawDatasetFilterProperties {
    /**
     * Master kill-switch.
     */
    private boolean enabled = true;

    /**
     * Controls how {@link Action#ALLOW} interacts with soft {@link Action#EXCLUDE} rules.
     */
    private OverridePolicy overridePolicy = new OverridePolicy();

    /**
     * Sample-level tracing.
     *
     * <p>When enabled, the filter emits per-sample decision traces at DEBUG level only.
     * Metrics are recorded regardless of this setting.
     */
    private Trace trace = new Trace();

    /**
     * Rules are evaluated in the following order:
     * <ol>
     *     <li><b>Hard excludes</b>: {@code action=EXCLUDE, hard=true}. These are always excluded.</li>
     *     <li><b>Allow</b>: {@code action=ALLOW}. When present, may override soft excludes depending on {@link #overridePolicy}.</li>
     *     <li><b>Soft excludes</b>: {@code action=EXCLUDE, hard=false}. These are excluded unless overridden by an allow rule.</li>
     * </ol>
     */
    private List<Rule> rules = new ArrayList<>();

    public enum Action {
        EXCLUDE,
        ALLOW
    }

    public enum Scope {
        PROMPT,
        ANSWER,
        MODEL_USED;

        public String key() {
            return switch (this) {
                case PROMPT -> "prompt";
                case ANSWER -> "answer";
                case MODEL_USED -> "modelUsed";
            };
        }
    }

    @Data
    public static class OverridePolicy {
        /**
         * Policy for resolving allow-group override matrix keys when more than one glob matches
         * a given allow rule group.
         */
        public enum AllowGroupOverrideMatrixMatchPolicy {
            /**
             * Select the most-specific matching allow-group glob key.
             *
             * <p>Specificity is computed as the number of non-wildcard characters in the glob.
             * If multiple keys tie for most-specific, {@link #allowGroupOverrideMatrixTiePolicy}
             * determines the winner.
             */
            MOST_SPECIFIC,

            /**
             * Select the first matching key in configuration order.
             */
            FIRST_MATCH,

            /**
             * Merge all matching keys' allowed override groups.
             */
            MERGE_ALL_MATCHES
        }

        /**
         * Tie-break policy used when {@link #allowGroupOverrideMatrixMatchPolicy} is
         * {@link AllowGroupOverrideMatrixMatchPolicy#MOST_SPECIFIC} and multiple keys have the same
         * specificity.
         */
        public enum AllowGroupOverrideMatrixTiePolicy {
            /**
             * Deterministic tie-break by lexicographic order of the allow-group glob key.
             */
            PICK_LEXICOGRAPHIC,

            /**
             * Tie-break by configuration order (first key wins).
             */
            PICK_FIRST_IN_CONFIG,

            /**
             * Merge allowed override groups across tied keys.
             */
            MERGE_TIED,

            /**
             * Deny overrides when a tie occurs (safe default if you want to surface config ambiguity).
             */
            DENY_TIED
        }
        /**
         * If true, {@link Action#ALLOW} rules may only override soft {@link Action#EXCLUDE} rules whose
         * {@link Rule#getGroup()} matches {@link #allowOverridesSoftExcludeGroups}.
         *
         * <p>If false (default), an {@link Action#ALLOW} rule overrides <i>any</i> soft exclude rule
         * (legacy behavior).
         */
        private boolean strictAllowOverrides = false;

        /**
         * Soft-exclude groups that {@link Action#ALLOW} rules are permitted to override when
         * {@link #strictAllowOverrides} is enabled.
         *
         * <p>Supports simple glob syntax via '*', e.g.:
         * <ul>
         *     <li>{@code modelUsed}</li>
         *     <li>{@code soft.modelUsed*}</li>
         * </ul>
         */
        private List<String> allowOverridesSoftExcludeGroups = new ArrayList<>();

        /**
         * Optional per-allow-group override matrix.
         *
         * <p>Key: allow rule's {@link Rule#getGroup()} (e.g. "modelUsed") supports simple glob syntax via '*'
         * (e.g. {@code soft.*}) or "*" for all allow groups.
         * Value: list of soft-exclude rule groups (globs) that the allow group can override.
         *
         * <p>When {@link #strictAllowOverrides} is enabled, the filter checks (in order):
         * <ol>
         *     <li>allow rule's own {@link Rule#getOverrideSoftExcludeGroups()} if set</li>
         *     <li>this matrix for the allow rule's group (or "*")</li>
         *     <li>{@link #allowOverridesSoftExcludeGroups} as a global fallback</li>
         * </ol>
         */
        private Map<String, List<String>> allowGroupOverrideMatrix = new HashMap<>();

        /**
         * Match policy for resolving {@link #allowGroupOverrideMatrix} keys.
         */
        private AllowGroupOverrideMatrixMatchPolicy allowGroupOverrideMatrixMatchPolicy =
            AllowGroupOverrideMatrixMatchPolicy.MOST_SPECIFIC;

        /**
         * Tie policy for resolving {@link #allowGroupOverrideMatrix} keys.
         */
        private AllowGroupOverrideMatrixTiePolicy allowGroupOverrideMatrixTiePolicy =
            AllowGroupOverrideMatrixTiePolicy.PICK_LEXICOGRAPHIC;

        /**
         * If true, decision metrics will include an additional low-cardinality tag
         * {@code override_source} with values like {@code none|rule|matrix|global}.
         */
        private boolean includeOverrideSourceInDecisionMetrics = false;

        /**
         * If true, decision metrics will include an additional low-cardinality tag
         * {@code override_matrix_resolution} when an allow-override decision used
         * {@link #allowGroupOverrideMatrix}.
         *
         * <p>Values are small and stable (e.g. {@code none}, {@code single_match},
         * {@code multi_match_first}, {@code multi_match_merged}, {@code multi_most_specific},
         * {@code multi_tie_first}, {@code multi_tie_lex}, ...).
         *
         * <p>Disabled by default to avoid surprising time-series splits.
         */
        private boolean includeOverrideMatrixResolutionInDecisionMetrics = false;

        /**
         * If true, log a WARN when {@link #allowGroupOverrideMatrix} resolution results in a tie
         * (ambiguous config), in addition to always recording tie counters.
         *
         * <p>Keep this off by default to avoid log noise in high-throughput systems.
         */
        private boolean warnOnOverrideMatrixTie = false;
    }

    @Data
    public static class Trace {
        /**
         * Enables per-sample trace logging at DEBUG level only.
         */
        private boolean enabled = false;

        /**
         * Whether to include a stable hash of (prompt, answer, modelUsed) to correlate traces
         * without logging raw content.
         */
        private boolean includeSampleHash = false;
    }

    @Data
    public static class Rule {
        /**
         * Unique name used for logging and metrics.
         */
        private String name = "";

        private Action action = Action.EXCLUDE;

        private Scope scope = Scope.ANSWER;

        /**
         * Logical group used for operations and override-policy decisions.
         */
        private String group = "default";

        /**
         * (ALLOW rules) Restricts which soft-exclude rule groups this allow rule is allowed to override
         * when {@link OverridePolicy#strictAllowOverrides} is enabled.
         *
         * <p>If empty, the filter falls back to {@link OverridePolicy#getAllowGroupOverrideMatrix()}
         * (keyed by allow rule group) and then {@link OverridePolicy#getAllowOverridesSoftExcludeGroups()}.
         */
        private List<String> overrideSoftExcludeGroups = new ArrayList<>();

        /**
         * If true, this exclude rule cannot be overridden.
         */
        private boolean hard = false;

        /**
         * Higher priority rules are evaluated first within the same stage.
         */
        private int priority = 0;

        /**
         * Regex patterns to match against the selected scope string.
         */
        private List<String> patterns = new ArrayList<>();
    }
}
