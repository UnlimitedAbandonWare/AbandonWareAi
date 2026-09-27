package ai.abandonware.subagent.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, bounded host-execution packet. Validation never reads or mutates the workspace. */
record GlmExecutionPacket(
        String goal,
        List<String> evidence,
        List<String> affectedFiles,
        List<String> nonGoals,
        String proposedPatch,
        String redTest,
        List<String> greenTests,
        List<String> regressionTests,
        String rollbackCondition,
        List<String> securityChecks,
        List<String> unknowns) {

    private static final int MAX_JSON_CHARS = 32_000;
    private static final int MAX_LIST_ITEMS = 32;
    private static final int MAX_ITEM_CHARS = 2_000;
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    static Validation validate(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return Validation.invalid("packet_missing");
        }
        String trimmed = rawOutput.trim();
        if (trimmed.length() > MAX_JSON_CHARS) {
            return Validation.invalid("packet_too_large");
        }
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Validation.invalid("packet_json_object_required");
        }
        GlmExecutionPacket packet;
        try {
            packet = STRICT_MAPPER.readValue(trimmed, GlmExecutionPacket.class);
        } catch (Exception ignored) {
            return Validation.invalid("packet_schema_invalid");
        }
        List<String> failures = packet.validationFailures();
        return failures.isEmpty()
                ? Validation.valid(packet.structuredView())
                : Validation.invalid(failures);
    }

    private List<String> validationFailures() {
        List<String> failures = new ArrayList<>();
        requireText(goal, 4_000, "goal", failures);
        requireText(proposedPatch, 16_000, "proposed_patch", failures);
        requireText(redTest, 4_000, "red_test", failures);
        requireText(rollbackCondition, 4_000, "rollback_condition", failures);
        validateList(evidence, "evidence", false, failures);
        validateList(affectedFiles, "affected_files", false, failures);
        validateList(nonGoals, "non_goals", false, failures);
        validateList(greenTests, "green_tests", false, failures);
        validateList(regressionTests, "regression_tests", false, failures);
        validateList(securityChecks, "security_checks", false, failures);
        validateList(unknowns, "unknowns", true, failures);
        if (affectedFiles != null) {
            affectedFiles.forEach(path -> validateRelativePath(path, failures));
        }
        return List.copyOf(failures.stream().distinct().toList());
    }

    private Map<String, Object> structuredView() {
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("goal", goal.trim());
        packet.put("evidence", boundedCopy(evidence));
        packet.put("affectedFiles", boundedCopy(affectedFiles));
        packet.put("nonGoals", boundedCopy(nonGoals));
        packet.put("proposedPatch", proposedPatch.trim());
        packet.put("redTest", redTest.trim());
        packet.put("greenTests", boundedCopy(greenTests));
        packet.put("regressionTests", boundedCopy(regressionTests));
        packet.put("rollbackCondition", rollbackCondition.trim());
        packet.put("securityChecks", boundedCopy(securityChecks));
        packet.put("unknowns", boundedCopy(unknowns));
        return Map.copyOf(packet);
    }

    private static List<String> boundedCopy(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim).toList();
    }

    private static void requireText(String value,
                                    int maximum,
                                    String field,
                                    List<String> failures) {
        if (value == null || value.isBlank()) {
            failures.add(field + "_required");
        } else if (value.length() > maximum) {
            failures.add(field + "_too_large");
        }
    }

    private static void validateList(List<String> values,
                                     String field,
                                     boolean emptyAllowed,
                                     List<String> failures) {
        if (values == null || (!emptyAllowed && values.isEmpty())) {
            failures.add(field + "_required");
            return;
        }
        if (values.size() > MAX_LIST_ITEMS) {
            failures.add(field + "_too_many");
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                failures.add(field + "_blank_item");
            } else if (value.length() > MAX_ITEM_CHARS) {
                failures.add(field + "_item_too_large");
            }
        }
    }

    private static void validateRelativePath(String rawPath, List<String> failures) {
        if (rawPath == null) {
            return;
        }
        String path = rawPath.trim().replace('\\', '/');
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (path.isBlank()
                || path.startsWith("/")
                || path.startsWith("//")
                || path.matches("^[A-Za-z]:.*")
                || path.equals("..")
                || path.startsWith("../")
                || path.contains("/../")
                || lower.equals(".git")
                || lower.startsWith(".git/")
                || lower.equals("__patch_drop__")
                || lower.startsWith("__patch_drop__/")) {
            failures.add("affected_files_unsafe_path");
        }
    }

    record Validation(String status,
                      List<String> reasonCodes,
                      Map<String, Object> packet) {

        private static Validation valid(Map<String, Object> packet) {
            return new Validation("VALID", List.of(), packet);
        }

        private static Validation invalid(String reasonCode) {
            return invalid(List.of(reasonCode));
        }

        private static Validation invalid(List<String> reasonCodes) {
            return new Validation("INVALID", List.copyOf(reasonCodes), Map.of());
        }

        boolean valid() {
            return "VALID".equals(status);
        }

        Map<String, Object> structuredView() {
            return Map.of(
                    "status", status,
                    "reasonCodes", reasonCodes,
                    "packet", packet);
        }
    }
}
