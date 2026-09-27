package com.example.lms.service.postprocess;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure completeness check for the explicit, machine-labeled S8 probe protocol.
 *
 * <p>This policy intentionally does not infer financial state from prose. It is
 * applicable only when the request supplies every allowlisted key/value marker
 * used by the synthetic probe.</p>
 */
public final class S8AnswerContractPolicy {

    private static final String OBSERVED_PREFIX = "OBSERVED_CONSTRAINTS:";
    private static final String INFERENCE_PREFIX = "INFERENCE:";
    private static final String INCOMPLETE_HOLD =
            "HOLD\n한계: S8 응답 계약이 완전하지 않아 제약이나 추론을 자동 생성하지 않았습니다.";
    private static final Pattern NEGATED_PROTOCOL_REQUEST = Pattern.compile(
            "(?i)\\b(?:do\\s+not|don['’]t)\\s+(?:apply|follow|use|enforce)\\b"
                    + "[^.\\r\\n]{0,160}(?:output\\s+contract|protocol|two\\s+labeled\\s+lines)");

    private static final Map<String, String> REQUIRED_OBSERVATIONS = Map.of(
            "debt", "present",
            "cashflow", "tight",
            "spendingLimit", "restricted",
            "riskTolerance", "low",
            "purchaseCost", "high");
    private static final Map<String, String> REQUIRED_INFERENCE =
            Map.of("discretionaryBudget", "unknown");
    private static final List<String> REQUIRED_QUERY_MARKERS = List.of(
            "Return exactly two labeled lines.",
            OBSERVED_PREFIX,
            "debt=present",
            "cashflow=tight",
            "spendingLimit=restricted",
            "riskTolerance=low",
            "purchaseCost=high",
            INFERENCE_PREFIX,
            "discretionaryBudget=unknown");

    public Result evaluate(String query, String answer) {
        if (!isExplicitStructuredRequest(query)) {
            return new Result(answer, false, "not_applicable");
        }
        if (isComplete(answer)) {
            return new Result(answer, false, "complete");
        }
        return new Result(INCOMPLETE_HOLD, true, "s8_incomplete_hold");
    }

    Integrity inspect(String query, String answer) {
        if (!isExplicitStructuredRequest(query)) {
            return new Integrity(false, 0, 0, 0, 0, false, false);
        }
        List<String> lines = answer == null
                ? List.of()
                : answer.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        Map<String, String> observations = lines.isEmpty()
                ? null
                : parseKeyValues(lines.get(0), OBSERVED_PREFIX);
        Map<String, String> inference = lines.size() < 2
                ? null
                : parseKeyValues(lines.get(1), INFERENCE_PREFIX);
        int preservedObservations = matchingEntryCount(REQUIRED_OBSERVATIONS, observations);
        int preservedInference = matchingEntryCount(REQUIRED_INFERENCE, inference);
        boolean orderedTwoLineContract = lines.size() == 2
                && observations != null
                && inference != null;
        boolean complete = orderedTwoLineContract
                && preservedObservations == REQUIRED_OBSERVATIONS.size()
                && preservedInference == REQUIRED_INFERENCE.size();
        return new Integrity(
                true,
                REQUIRED_OBSERVATIONS.size(),
                preservedObservations,
                REQUIRED_INFERENCE.size(),
                preservedInference,
                orderedTwoLineContract,
                complete);
    }

    private static int matchingEntryCount(Map<String, String> required, Map<String, String> actual) {
        if (actual == null || actual.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, String> entry : required.entrySet()) {
            if (entry.getValue().equals(actual.get(entry.getKey()))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isExplicitStructuredRequest(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        if (NEGATED_PROTOCOL_REQUEST.matcher(query).find()) {
            return false;
        }
        for (String marker : REQUIRED_QUERY_MARKERS) {
            if (!query.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isComplete(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        if (INCOMPLETE_HOLD.equals(answer.strip())) {
            return true;
        }
        List<String> lines = answer.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.size() != 2) {
            return false;
        }
        Map<String, String> observations = parseKeyValues(lines.get(0), OBSERVED_PREFIX);
        Map<String, String> inference = parseKeyValues(lines.get(1), INFERENCE_PREFIX);
        return REQUIRED_OBSERVATIONS.equals(observations)
                && REQUIRED_INFERENCE.equals(inference);
    }

    private static Map<String, String> parseKeyValues(String line, String prefix) {
        if (line == null || !line.startsWith(prefix)) {
            return null;
        }
        String body = line.substring(prefix.length()).strip();
        if (body.endsWith(".")) {
            body = body.substring(0, body.length() - 1).strip();
        }
        if (body.isBlank()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String entry : body.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                return null;
            }
            String key = entry.substring(0, separator).strip();
            String value = entry.substring(separator + 1).strip();
            if (key.isBlank() || value.isBlank() || values.putIfAbsent(key, value) != null) {
                return null;
            }
        }
        return values;
    }

    public record Result(String content, boolean changed, String reasonCode) {
    }

    record Integrity(
            boolean applicable,
            int requiredObservationCount,
            int preservedObservationCount,
            int requiredInferenceCount,
            int preservedInferenceCount,
            boolean orderedTwoLineContract,
            boolean complete) {
    }
}
