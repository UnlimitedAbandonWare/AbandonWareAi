package com.example.lms.ensemble;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, model-independent scoring for bounded SUPPORT/FALSIFY claim rows. */
final class DualHypothesisEvidenceScorer {

    private static final int MAX_DOSSIER_LENGTH = 2_000;
    private static final int MAX_CLAIMS = 12;
    private static final Pattern DIRECTION = Pattern.compile(
            "^DIRECTION:\\s*(SUPPORT|FALSIFY)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIM = Pattern.compile(
            "^CLAIM:\\s*(.{1,512}?)\\s*\\|\\s*EVIDENCE:\\s*(.*?)\\s*\\|\\s*STATUS:\\s*"
                    + "(SUPPORTED|CONTRADICTED|UNSUPPORTED)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCLUSION = Pattern.compile(
            "^CONCLUSION:\\s*(.{1,512})\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EVIDENCE_ID = Pattern.compile(
            "\\bev1:[0-9a-f]{12}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EVIDENCE_LIST = Pattern.compile(
            "^(?:NONE|ev1:[0-9a-f]{12}(?:\\s*,\\s*ev1:[0-9a-f]{12})*)$",
            Pattern.CASE_INSENSITIVE);

    private DualHypothesisEvidenceScorer() {
    }

    static Score score(
            String dossier,
            SampledCandidate.HypothesisDirection expectedDirection,
            EnsembleEvidenceMatrix matrix) {
        return score("", dossier, expectedDirection, matrix);
    }

    static Score score(
            String prompt,
            String dossier,
            SampledCandidate.HypothesisDirection expectedDirection,
            EnsembleEvidenceMatrix matrix) {
        EvaluationIntegrityPostProcessor.Audit integrityAudit =
                EvaluationIntegrityPostProcessor.inspect(prompt, dossier);
        if (dossier == null
                || dossier.isBlank()
                || dossier.length() > MAX_DOSSIER_LENGTH
                || dossier.indexOf('\0') >= 0
                || !isBoundedAscii(dossier)
                || expectedDirection == null
                || expectedDirection == SampledCandidate.HypothesisDirection.LEGACY
                || integrityAudit.quarantineRequired()) {
            return Score.invalid(integrityAudit);
        }

        List<EnsembleEvidenceMatrix.Row> rows = matrix == null ? List.of() : matrix.rows();
        Map<String, EnsembleEvidenceMatrix.Row> rowsById = new HashMap<>();
        for (EnsembleEvidenceMatrix.Row row : rows) {
            if (row != null && row.evidenceId() != null) {
                rowsById.put(row.evidenceId().toLowerCase(Locale.ROOT), row);
            }
        }

        int phase = 0;
        int claimCount = 0;
        int validEvidenceClaimCount = 0;
        int contradictedClaimCount = 0;
        int unsupportedClaimCount = 0;
        Set<String> citedProvenanceGroups = new HashSet<>();

        for (String rawLine : dossier.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (phase == 0) {
                Matcher directionMatcher = DIRECTION.matcher(line);
                if (!directionMatcher.matches()
                        || !expectedDirection.name().equals(
                                directionMatcher.group(1).toUpperCase(Locale.ROOT))) {
                    return Score.invalid(integrityAudit);
                }
                phase = 1;
                continue;
            }
            if (phase == 2) {
                return Score.invalid(integrityAudit);
            }
            Matcher claimMatcher = CLAIM.matcher(line);
            if (claimMatcher.matches()) {
                claimCount++;
                if (claimCount > MAX_CLAIMS) {
                    return Score.invalid(integrityAudit);
                }
                String status = claimMatcher.group(3).toUpperCase(Locale.ROOT);
                if ("CONTRADICTED".equals(status)) {
                    contradictedClaimCount++;
                } else if ("UNSUPPORTED".equals(status)) {
                    unsupportedClaimCount++;
                }

                String evidenceList = claimMatcher.group(2).strip();
                if (!EVIDENCE_LIST.matcher(evidenceList).matches()) {
                    return Score.invalid(integrityAudit);
                }
                if ("NONE".equalsIgnoreCase(evidenceList) && !"UNSUPPORTED".equals(status)) {
                    return Score.invalid(integrityAudit);
                }
                Set<String> validIdsForClaim = new HashSet<>();
                Matcher evidenceMatcher = EVIDENCE_ID.matcher(evidenceList);
                while (evidenceMatcher.find()) {
                    String evidenceId = evidenceMatcher.group().toLowerCase(Locale.ROOT);
                    EnsembleEvidenceMatrix.Row row = rowsById.get(evidenceId);
                    if (row != null
                            && matrix != null
                            && matrix.isGroundingEvidenceId(evidenceId)
                            && validIdsForClaim.add(evidenceId)) {
                        citedProvenanceGroups.add(row.provenanceGroupId());
                    }
                }
                if (!validIdsForClaim.isEmpty()) {
                    validEvidenceClaimCount++;
                }
                continue;
            }
            Matcher conclusionMatcher = CONCLUSION.matcher(line);
            if (conclusionMatcher.matches()) {
                if (claimCount == 0) {
                    return Score.invalid(integrityAudit);
                }
                phase = 2;
                continue;
            }
            return Score.invalid(integrityAudit);
        }

        if (phase != 2 || claimCount == 0) {
            return Score.invalid(integrityAudit);
        }

        double evidenceRate = ratio(validEvidenceClaimCount, claimCount);
        double sourceDiversity = matrix == null || matrix.groundingProvenanceGroupCount() <= 0
                ? 0.0d
                : ratio(citedProvenanceGroups.size(), matrix.groundingProvenanceGroupCount());
        double contradictionRate = ratio(contradictedClaimCount, claimCount);
        double codeOwnedGroundingScore = validEvidenceClaimCount == 0
                ? 0.0d
                : clamp01((0.70d * evidenceRate)
                        + (0.20d * sourceDiversity)
                        + (0.10d * (1.0d - contradictionRate)));
        double groundingScore = integrityAudit.correctedScore(codeOwnedGroundingScore);
        SampledCandidate.EvidenceStatus evidenceStatus =
                validEvidenceClaimCount == claimCount
                        && contradictedClaimCount == 0
                        && unsupportedClaimCount == 0
                        ? SampledCandidate.EvidenceStatus.SUFFICIENT
                        : SampledCandidate.EvidenceStatus.INSUFFICIENT_OR_CONTRADICTED;

        return new Score(
                true,
                claimCount,
                validEvidenceClaimCount,
                evidenceRate,
                sourceDiversity,
                contradictionRate,
                groundingScore,
                evidenceStatus,
                integrityAudit);
    }

    private static double ratio(int numerator, int denominator) {
        if (numerator <= 0 || denominator <= 0) {
            return 0.0d;
        }
        return clamp01((double) numerator / (double) denominator);
    }

    private static boolean isBoundedAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current > 0x7e
                    || (current < 0x20 && current != '\r' && current != '\n' && current != '\t')) {
                return false;
            }
        }
        return true;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    record Score(
            boolean validContract,
            int claimCount,
            int validEvidenceClaimCount,
            double evidenceRate,
            double sourceDiversity,
            double contradictionRate,
            double groundingScore,
            SampledCandidate.EvidenceStatus evidenceStatus,
            EvaluationIntegrityPostProcessor.Audit integrityAudit) {

        Score {
            integrityAudit = integrityAudit == null
                    ? EvaluationIntegrityPostProcessor.Audit.none()
                    : integrityAudit;
        }

        private static Score invalid(EvaluationIntegrityPostProcessor.Audit integrityAudit) {
            return new Score(
                    false,
                    0,
                    0,
                    0.0d,
                    0.0d,
                    0.0d,
                    0.0d,
                    SampledCandidate.EvidenceStatus.INSUFFICIENT_OR_CONTRADICTED,
                    integrityAudit);
        }
    }
}
