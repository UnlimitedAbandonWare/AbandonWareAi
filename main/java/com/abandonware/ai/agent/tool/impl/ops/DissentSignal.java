package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One bounded weak signal that may authorize a counter-evidence probe.
 *
 * <p>The raw observation, provenance labels, and decision impacts remain local
 * to the invocation. Public tool output exposes only stable hashes and rule
 * codes. This object never owns a verdict or a release gate.</p>
 */
final class DissentSignal {

    private static final int MAX_OBSERVATION_CHARS = 1_024;
    private static final int MAX_LABEL_CHARS = 128;
    private static final int MAX_IMPACT_CHARS = 256;
    private static final int MAX_LIST_ITEMS = 16;

    private final boolean present;
    private final String atomicObservation;
    private final String provenanceGroup;
    private final List<String> correlatedMajorityProvenanceGroups;
    private final List<String> collapsedFrom;
    private final String specificity;
    private final boolean decisionChanging;
    private final String ifCorroborated;
    private final String ifDisconfirmed;
    private final String family;

    private DissentSignal(
            boolean present,
            String atomicObservation,
            String provenanceGroup,
            List<String> correlatedMajorityProvenanceGroups,
            List<String> collapsedFrom,
            String specificity,
            boolean decisionChanging,
            String ifCorroborated,
            String ifDisconfirmed,
            String family) {
        this.present = present;
        this.atomicObservation = atomicObservation;
        this.provenanceGroup = provenanceGroup;
        this.correlatedMajorityProvenanceGroups = correlatedMajorityProvenanceGroups;
        this.collapsedFrom = collapsedFrom;
        this.specificity = specificity;
        this.decisionChanging = decisionChanging;
        this.ifCorroborated = ifCorroborated;
        this.ifDisconfirmed = ifDisconfirmed;
        this.family = family;
    }

    static DissentSignal from(Object value) {
        if (value == null) {
            return missing();
        }
        if (!(value instanceof Map<?, ?> input)) {
            throw ToolInvocationException.badRequest("dissent_signal_invalid");
        }
        String observation = requiredText(
                input.get("atomicObservation"), MAX_OBSERVATION_CHARS,
                "dissent_signal_observation_required");
        String provenanceGroup = requiredText(
                input.get("provenanceGroup"), MAX_LABEL_CHARS,
                "dissent_signal_provenance_required");
        List<String> majorityGroups = boundedLabels(
                input.get("correlatedMajorityProvenanceGroups"), false,
                "dissent_signal_majority_provenance_invalid");
        List<String> collapsedFrom = boundedLabels(
                input.get("collapsedFrom"), true,
                "dissent_signal_collapsed_from_invalid");
        String specificity = canonicalEnum(
                input.get("specificity"), List.of("low", "medium", "high"),
                "dissent_signal_specificity_invalid");
        boolean decisionChanging = requiredBoolean(
                input.get("decisionChanging"), "dissent_signal_decision_changing_invalid");
        if (!(input.get("decisionImpact") instanceof Map<?, ?> decisionImpact)) {
            throw ToolInvocationException.badRequest("dissent_signal_decision_impact_invalid");
        }
        String ifCorroborated = requiredText(
                decisionImpact.get("ifCorroborated"), MAX_IMPACT_CHARS,
                "dissent_signal_corroborated_impact_required");
        String ifDisconfirmed = requiredText(
                decisionImpact.get("ifDisconfirmed"), MAX_IMPACT_CHARS,
                "dissent_signal_disconfirmed_impact_required");
        String family = canonicalEnum(
                input.get("family"),
                List.of("authoritative_constraint", "alternative_or_unknown", "provenance_and_time"),
                "dissent_signal_family_invalid");
        return new DissentSignal(
                true,
                observation,
                provenanceGroup,
                majorityGroups,
                collapsedFrom,
                specificity,
                decisionChanging,
                ifCorroborated,
                ifDisconfirmed,
                family);
    }

    boolean eligible() {
        return "eligible".equals(reason());
    }

    String reason() {
        if (!present) {
            return "dissent_signal_missing";
        }
        if (correlatedMajorityProvenanceGroups.isEmpty()) {
            return "majority_provenance_missing";
        }
        String normalizedGroup = normalize(provenanceGroup);
        if (correlatedMajorityProvenanceGroups.stream()
                .map(DissentSignal::normalize)
                .anyMatch(normalizedGroup::equals)) {
            return "correlated_provenance";
        }
        if (!decisionChanging) {
            return "not_decision_changing";
        }
        if ("low".equals(specificity)) {
            return "insufficient_specificity";
        }
        if (normalize(ifCorroborated).equals(normalize(ifDisconfirmed))) {
            return "decision_impact_not_distinct";
        }
        return "eligible";
    }

    String signalRef() {
        if (!present) {
            return null;
        }
        StringBuilder canonical = new StringBuilder("dissent-signal.v1");
        appendCanonical(canonical, atomicObservation);
        appendCanonical(canonical, provenanceGroup);
        appendCanonical(canonical, Integer.toString(correlatedMajorityProvenanceGroups.size()));
        correlatedMajorityProvenanceGroups.forEach(value -> appendCanonical(canonical, value));
        appendCanonical(canonical, Integer.toString(collapsedFrom.size()));
        collapsedFrom.forEach(value -> appendCanonical(canonical, value));
        appendCanonical(canonical, specificity);
        appendCanonical(canonical, Boolean.toString(decisionChanging));
        appendCanonical(canonical, ifCorroborated);
        appendCanonical(canonical, ifDisconfirmed);
        appendCanonical(canonical, family);
        return SafeRedactor.hashValue(canonical.toString());
    }

    String provenanceRef() {
        return present ? SafeRedactor.hashValue(provenanceGroup) : null;
    }

    String family() {
        return present ? family : "none";
    }

    String bindConstraintHash(String constraintHash) {
        if (!eligible()) {
            return constraintHash;
        }
        return SafeRedactor.hashValue(constraintHash + "|" + signalRef());
    }

    private static DissentSignal missing() {
        return new DissentSignal(
                false, "", "", List.of(), List.of(), "low", false, "", "", "none");
    }

    private static String requiredText(Object value, int maxChars, String code) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank() || text.length() > maxChars) {
            throw ToolInvocationException.badRequest(code);
        }
        return text;
    }

    private static List<String> boundedLabels(Object value, boolean allowMissing, String code) {
        if (value == null && allowMissing) {
            return List.of();
        }
        if (!(value instanceof Collection<?> values) || values.size() > MAX_LIST_ITEMS) {
            throw ToolInvocationException.badRequest(code);
        }
        ArrayList<String> labels = new ArrayList<>();
        for (Object item : values) {
            String label = item == null ? "" : String.valueOf(item).trim();
            if (label.isBlank() || label.length() > MAX_LABEL_CHARS) {
                throw ToolInvocationException.badRequest(code);
            }
            labels.add(label);
        }
        return List.copyOf(labels);
    }

    private static boolean requiredBoolean(Object value, String code) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw ToolInvocationException.badRequest(code);
    }

    private static String canonicalEnum(Object value, List<String> allowed, String code) {
        String text = value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(text)) {
            throw ToolInvocationException.badRequest(code);
        }
        return text;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void appendCanonical(StringBuilder out, String value) {
        String safe = value == null ? "" : value;
        out.append('|').append(safe.length()).append(':').append(safe);
    }
}
