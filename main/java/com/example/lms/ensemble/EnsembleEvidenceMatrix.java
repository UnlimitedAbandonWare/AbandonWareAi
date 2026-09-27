package com.example.lms.ensemble;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class EnsembleEvidenceMatrix {

    private static final String SCHEMA_VERSION = "ensemble-evidence-matrix.v1";
    private static final int MAX_ROWS = 12;
    private static final Pattern SAFE_MARKER = Pattern.compile("^[PWVD][0-9]{1,6}$");
    private static final Set<String> KNOWN_KINDS = Set.of("WEB", "VECTOR", "LOCAL_DOC");

    private final List<Row> rows;
    private final int droppedMissingLocatorCount;
    private final int droppedRowLimitCount;
    private final Set<String> blockers;
    private final String matrixId;

    private EnsembleEvidenceMatrix(
            List<Row> rows,
            int droppedMissingLocatorCount,
            int droppedRowLimitCount) {
        this.rows = rows == null ? List.of() : List.copyOf(rows);
        this.droppedMissingLocatorCount = Math.max(0, droppedMissingLocatorCount);
        this.droppedRowLimitCount = Math.max(0, droppedRowLimitCount);
        LinkedHashSet<String> foundBlockers = new LinkedHashSet<>();
        if (this.rows.isEmpty()) {
            foundBlockers.add("NO_EVIDENCE");
        }
        if (this.droppedMissingLocatorCount > 0) {
            foundBlockers.add("MISSING_LOCATOR");
        }
        if (this.droppedRowLimitCount > 0) {
            foundBlockers.add("ROW_LIMIT_APPLIED");
        }
        foundBlockers.add("TIME_UNKNOWN");
        foundBlockers.add("DIRECTNESS_UNKNOWN");
        foundBlockers.add("RELATION_UNKNOWN");
        foundBlockers.add("INDEPENDENCE_UNVERIFIED");
        this.blockers = Set.copyOf(foundBlockers);
        String packetState = SCHEMA_VERSION
                + "|droppedMissingLocatorCount=" + this.droppedMissingLocatorCount
                + "|droppedRowLimitCount=" + this.droppedRowLimitCount
                + "|blockers=" + String.join(",", this.blockers.stream().sorted().toList());
        String canonicalRows = this.rows.stream()
                .map(Row::canonicalIdentity)
                .sorted()
                .reduce(packetState, (left, right) -> left + "\n" + right);
        this.matrixId = "em1:" + SafeRedactor.hash12(canonicalRows);
    }

    static EnsembleEvidenceMatrix from(List<RagEvidenceMetadata> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return new EnsembleEvidenceMatrix(List.of(), 0, 0);
        }
        Map<String, Row> uniqueRows = new LinkedHashMap<>();
        int missingLocatorCount = 0;
        for (RagEvidenceMetadata item : evidence) {
            if (item == null) {
                missingLocatorCount++;
                continue;
            }
            Locator locator = locator(item);
            if (locator == null) {
                missingLocatorCount++;
                continue;
            }
            String kind = safeKind(item.kind());
            String lineRange = (item.lineStart() == null ? "?" : item.lineStart())
                    + "-"
                    + (item.lineEnd() == null ? "?" : item.lineEnd());
            String evidenceId = "ev1:" + SafeRedactor.hash12(
                    locator.canonicalLocator() + "|" + kind + "|" + lineRange);
            Row row = new Row(
                    evidenceId,
                    safeMarker(item.marker()),
                    kind,
                    "src1:" + SafeRedactor.hash12(locator.canonicalLocator()),
                    "pg1:" + SafeRedactor.hash12(locator.provenanceGroup()),
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNKNOWN",
                    "UNVERIFIED",
                    "UNKNOWN",
                    "UNKNOWN");
            uniqueRows.merge(evidenceId, row, EnsembleEvidenceMatrix::preferredRow);
        }
        List<Row> ordered = new ArrayList<>(uniqueRows.values());
        ordered.sort(Comparator.comparing(Row::evidenceId));
        int droppedRowLimitCount = Math.max(0, ordered.size() - MAX_ROWS);
        if (droppedRowLimitCount > 0) {
            ordered = new ArrayList<>(ordered.subList(0, MAX_ROWS));
        }
        return new EnsembleEvidenceMatrix(ordered, missingLocatorCount, droppedRowLimitCount);
    }

    String schemaVersion() {
        return SCHEMA_VERSION;
    }

    String matrixId() {
        return matrixId;
    }

    List<Row> rows() {
        return rows;
    }

    Set<String> evidenceIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        rows.forEach(row -> ids.add(row.evidenceId()));
        return Set.copyOf(ids);
    }

    int provenanceGroupCount() {
        return (int) rows.stream().map(Row::provenanceGroupId).distinct().count();
    }

    int groundingProvenanceGroupCount() {
        return (int) rows.stream()
                .filter(EnsembleEvidenceMatrix::isGroundingRow)
                .map(Row::provenanceGroupId)
                .distinct()
                .count();
    }

    int droppedMissingLocatorCount() {
        return droppedMissingLocatorCount;
    }

    int droppedRowLimitCount() {
        return droppedRowLimitCount;
    }

    boolean supportEligible() {
        return List.of("COOPERATIVE", "BASE_RATE", "OPPORTUNISTIC").stream()
                .anyMatch(stance -> {
                    List<Row> eligibleRows = rows.stream()
                            .filter(row -> isVerifiedSupportRow(row, stance))
                            .toList();
                    return eligibleRows.size() >= 2
                            && eligibleRows.stream().map(Row::provenanceGroupId).distinct().count() >= 2;
                });
    }

    Set<String> blockers() {
        return blockers;
    }

    boolean containsAllEvidenceIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        Set<String> canonicalIds = ids.stream()
                .map(EnsembleEvidenceMatrix::canonicalEvidenceId)
                .collect(java.util.stream.Collectors.toSet());
        return canonicalIds.size() == ids.size() && evidenceIds().containsAll(canonicalIds);
    }

    boolean isGroundingEvidenceId(String id) {
        String canonicalId = canonicalEvidenceId(id);
        return rows.stream().anyMatch(row -> row.evidenceId().equals(canonicalId) && isGroundingRow(row));
    }

    boolean supportsDebugPatchDecisionIds(List<String> ids) {
        if (ids == null || ids.size() < 2 || !containsAllEvidenceIds(ids)) {
            return false;
        }
        Set<String> canonicalIds = ids.stream()
                .map(EnsembleEvidenceMatrix::canonicalEvidenceId)
                .collect(java.util.stream.Collectors.toSet());
        List<Row> selectedRows = rows.stream()
                .filter(row -> canonicalIds.contains(row.evidenceId()))
                .toList();
        return selectedRows.stream().anyMatch(row -> row.safeMarker().startsWith("P"))
                && selectedRows.stream().anyMatch(row -> row.safeMarker().startsWith("D"));
    }

    boolean supportsDecisionIds(List<String> ids, String selectedStance) {
        String normalizedStance = selectedStance == null
                ? ""
                : selectedStance.strip().toUpperCase(Locale.ROOT);
        if (ids == null || ids.size() < 2
                || !Set.of("COOPERATIVE", "BASE_RATE", "OPPORTUNISTIC").contains(normalizedStance)) {
            return false;
        }
        Set<String> canonicalIds = ids.stream()
                .map(EnsembleEvidenceMatrix::canonicalEvidenceId)
                .collect(java.util.stream.Collectors.toSet());
        if (canonicalIds.size() != ids.size()) {
            return false;
        }
        List<Row> selectedRows = rows.stream()
                .filter(row -> canonicalIds.contains(row.evidenceId()))
                .toList();
        return selectedRows.size() == canonicalIds.size()
                && selectedRows.stream().allMatch(row -> isVerifiedSupportRow(row, normalizedStance))
                && selectedRows.stream().map(Row::provenanceGroupId).distinct().count() >= 2;
    }

    String renderForJudge() {
        StringBuilder out = new StringBuilder();
        out.append("### BEGIN EVIDENCE MATRIX\n");
        out.append("schemaVersion=").append(schemaVersion()).append('\n');
        out.append("matrixId=").append(matrixId()).append('\n');
        out.append("rowCount=").append(rows.size()).append('\n');
        out.append("provenanceGroupCount=").append(provenanceGroupCount()).append('\n');
        out.append("droppedRowLimitCount=").append(droppedRowLimitCount()).append('\n');
        out.append("supportEligible=").append(supportEligible()).append('\n');
        out.append("blockers=").append(String.join(",", blockers.stream().sorted().toList())).append('\n');
        for (Row row : rows) {
            out.append('[').append(row.evidenceId()).append("] ")
                    .append("safeMarker=").append(row.safeMarker())
                    .append("; kind=").append(row.kind())
                    .append("; sourceRef=").append(row.sourceRef())
                    .append("; provenanceGroupId=").append(row.provenanceGroupId())
                    .append("; observedAt=").append(row.observedAt())
                    .append("; validAt=").append(row.validAt())
                    .append("; directness=").append(row.directness())
                    .append("; independence=").append(row.independence())
                    .append("; relation=").append(row.relation())
                    .append("; coverage=").append(row.coverage())
                    .append('\n');
        }
        out.append("### END EVIDENCE MATRIX");
        return out.toString();
    }

    private static Locator locator(RagEvidenceMetadata item) {
        String canonicalUrl = canonicalPublicUrl(item.source());
        if (canonicalUrl != null) {
            URI uri = URI.create(canonicalUrl);
            int port = uri.getPort();
            String group = "url-host:" + canonicalHost(uri.getHost())
                    + (port < 0 ? "" : ":" + port);
            return new Locator("url:" + canonicalUrl, group);
        }
        String filePath = canonicalFilePath(item.filePath());
        if (filePath != null) {
            return new Locator("file:" + filePath, "file:" + filePath);
        }
        return null;
    }

    private static String canonicalPublicUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("http".equals(scheme) || "https".equals(scheme)) || host == null || host.isBlank()) {
                return null;
            }
            URI normalizedUri = uri.normalize();
            String rawPath = normalizedUri.getRawPath();
            String path = rawPath == null || rawPath.isBlank() ? "/" : rawPath;
            path = normalizeUnreservedEscapes(path);
            int port = uri.getPort();
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
                port = -1;
            }
            String normalizedHost = canonicalHost(host);
            String renderedHost = normalizedHost.contains(":") ? "[" + normalizedHost + "]" : normalizedHost;
            return scheme + "://" + renderedHost + (port < 0 ? "" : ":" + port) + path;
        } catch (RuntimeException ignored) {
            TraceStore.put("ensemble.evidenceMatrix.invalidPublicUrl", true);
            TraceStore.put("ensemble.evidenceMatrix.invalidPublicUrl.reason", "invalid_uri");
            return null;
        }
    }

    private static String canonicalFilePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\\', '/').replaceAll("/+", "/").strip().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static String safeMarker(String marker) {
        String normalized = marker == null ? "" : marker.strip().toUpperCase(Locale.ROOT);
        return SAFE_MARKER.matcher(normalized).matches() ? normalized : "UNKNOWN";
    }

    private static String safeKind(String kind) {
        String normalized = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
        return KNOWN_KINDS.contains(normalized) ? normalized : "OTHER";
    }

    private static String canonicalEvidenceId(String evidenceId) {
        return evidenceId == null ? "" : evidenceId.strip().toLowerCase(Locale.ROOT);
    }

    private static Row preferredRow(Row left, Row right) {
        int leftUnknown = "UNKNOWN".equals(left.safeMarker()) ? 1 : 0;
        int rightUnknown = "UNKNOWN".equals(right.safeMarker()) ? 1 : 0;
        if (leftUnknown != rightUnknown) {
            return leftUnknown < rightUnknown ? left : right;
        }
        return left.safeMarker().compareTo(right.safeMarker()) <= 0 ? left : right;
    }

    private static String normalizeUnreservedEscapes(String rawPath) {
        StringBuilder normalized = new StringBuilder(rawPath.length());
        for (int i = 0; i < rawPath.length(); i++) {
            char current = rawPath.charAt(i);
            if (current == '%' && i + 2 < rawPath.length()) {
                int high = Character.digit(rawPath.charAt(i + 1), 16);
                int low = Character.digit(rawPath.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    char decoded = (char) ((high << 4) + low);
                    if (isUnreserved(decoded)) {
                        normalized.append(decoded);
                    } else {
                        normalized.append('%')
                                .append(Character.toUpperCase(rawPath.charAt(i + 1)))
                                .append(Character.toUpperCase(rawPath.charAt(i + 2)));
                    }
                    i += 2;
                    continue;
                }
            }
            normalized.append(current);
        }
        return normalized.toString();
    }

    private static boolean isUnreserved(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }

    private static String canonicalHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isVerifiedSupportRow(Row row, String selectedStance) {
        return row != null
                && !"UNKNOWN".equals(row.observedAt())
                && !"UNKNOWN".equals(row.validAt())
                && "DIRECT".equals(row.directness())
                && "VERIFIED".equals(row.independence())
                && ("SUPPORTS_" + selectedStance).equals(row.relation())
                && !"UNKNOWN".equals(row.coverage());
    }

    private static boolean isGroundingRow(Row row) {
        return row != null
                && (row.safeMarker().startsWith("W")
                || row.safeMarker().startsWith("V")
                || row.safeMarker().startsWith("D"));
    }

    record Row(
            String evidenceId,
            String safeMarker,
            String kind,
            String sourceRef,
            String provenanceGroupId,
            String observedAt,
            String validAt,
            String directness,
            String independence,
            String relation,
            String coverage) {

        private String canonicalIdentity() {
            return String.join("|",
                    evidenceId,
                    safeMarker,
                    kind,
                    sourceRef,
                    provenanceGroupId,
                    observedAt,
                    validAt,
                    directness,
                    independence,
                    relation,
                    coverage);
        }
    }

    private record Locator(String canonicalLocator, String provenanceGroup) {
    }
}
