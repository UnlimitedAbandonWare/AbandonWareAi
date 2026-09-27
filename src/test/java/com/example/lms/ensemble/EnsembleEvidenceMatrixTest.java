package com.example.lms.ensemble;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnsembleEvidenceMatrixTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void evidenceIdsIgnoreMarkerRankAndTitle() {
        EnsembleEvidenceMatrix first = EnsembleEvidenceMatrix.from(List.of(evidence(
                "W1", "first title", "https://example.com/report", null, 10, 12, 1)));
        EnsembleEvidenceMatrix reordered = EnsembleEvidenceMatrix.from(List.of(evidence(
                "V99", "renamed title", "https://example.com/report", null, 10, 12, 99)));

        assertEquals(first.rows().get(0).evidenceId(), reordered.rows().get(0).evidenceId());
    }

    @Test
    void matrixIdIsStableAcrossInputOrder() {
        RagEvidenceMetadata one = evidence(
                "W1", "one", "https://one.example/report", null, 1, 2, 1);
        RagEvidenceMetadata two = evidence(
                "W2", "two", "https://two.example/report", null, 3, 4, 2);

        assertEquals(
                EnsembleEvidenceMatrix.from(List.of(one, two)).matrixId(),
                EnsembleEvidenceMatrix.from(List.of(two, one)).matrixId());
    }

    @Test
    void matrixIdChangesWhenCanonicalLocatorOrLineRangeChanges() {
        EnsembleEvidenceMatrix baseline = EnsembleEvidenceMatrix.from(List.of(evidence(
                "W1", "one", "https://example.com/report", null, 1, 2, 1)));
        EnsembleEvidenceMatrix changedLine = EnsembleEvidenceMatrix.from(List.of(evidence(
                "W1", "one", "https://example.com/report", null, 2, 3, 1)));
        EnsembleEvidenceMatrix changedLocator = EnsembleEvidenceMatrix.from(List.of(evidence(
                "W1", "one", "https://example.com/other", null, 1, 2, 1)));

        assertNotEquals(baseline.matrixId(), changedLine.matrixId());
        assertNotEquals(baseline.matrixId(), changedLocator.matrixId());
    }

    @Test
    void matrixIdChangesWhenRenderedMarkerOrDroppedStateChanges() {
        RagEvidenceMetadata validW1 = evidence(
                "W1", "one", "https://example.com/report", null, 1, 2, 1);
        RagEvidenceMetadata validW2 = evidence(
                "W2", "one", "https://example.com/report", null, 1, 2, 1);
        RagEvidenceMetadata missing = evidence(
                "W3", "missing", null, null, null, null, 3);

        EnsembleEvidenceMatrix baseline = EnsembleEvidenceMatrix.from(List.of(validW1));
        EnsembleEvidenceMatrix markerChanged = EnsembleEvidenceMatrix.from(List.of(validW2));
        EnsembleEvidenceMatrix droppedStateChanged = EnsembleEvidenceMatrix.from(List.of(validW1, missing));

        assertNotEquals(baseline.matrixId(), markerChanged.matrixId());
        assertNotEquals(baseline.matrixId(), droppedStateChanged.matrixId());
        assertEquals(baseline.evidenceIds(), markerChanged.evidenceIds());
    }

    @Test
    void duplicateCanonicalRowsMergeMarkerDeterministicallyAcrossInputOrder() {
        RagEvidenceMetadata w1 = evidence(
                "W1", "one", "https://example.com/report", null, 1, 2, 1);
        RagEvidenceMetadata w2 = evidence(
                "W2", "two", "https://example.com/report", null, 1, 2, 2);

        EnsembleEvidenceMatrix forward = EnsembleEvidenceMatrix.from(List.of(w1, w2));
        EnsembleEvidenceMatrix reverse = EnsembleEvidenceMatrix.from(List.of(w2, w1));

        assertEquals(forward.matrixId(), reverse.matrixId());
        assertEquals(forward.renderForJudge(), reverse.renderForJudge());
        assertEquals("W1", forward.rows().get(0).safeMarker());
    }

    @Test
    void unreservedPercentEncodingHasStableIdentity() {
        EnsembleEvidenceMatrix encoded = EnsembleEvidenceMatrix.from(List.of(evidence(
                "W1", "one", "https://example.com/%7Euser/report", null, 1, 2, 1)));
        EnsembleEvidenceMatrix decoded = EnsembleEvidenceMatrix.from(List.of(evidence(
                "W1", "one", "https://example.com/~user/report", null, 1, 2, 1)));

        assertEquals(encoded.evidenceIds(), decoded.evidenceIds());
        assertEquals(encoded.matrixId(), decoded.matrixId());
    }

    @Test
    void explicitDefaultPortSharesProvenanceGroup() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(List.of(
                evidence("W1", "one", "https://example.com:443/a", null, 1, 2, 1),
                evidence("W2", "two", "https://example.com/b", null, 3, 4, 2)));

        assertEquals(1, matrix.provenanceGroupCount());
    }

    @Test
    void ipv6PublicUrlCanonicalizesWithoutThrowingOrDoubleBracketing() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(List.of(evidence(
                "W1", "ipv6", "https://[2001:db8::1]/report", null, 1, 2, 1)));

        assertEquals(1, matrix.rows().size());
        assertEquals(1, matrix.provenanceGroupCount());
        assertTrue(matrix.rows().get(0).sourceRef().startsWith("src1:"));
        assertFalse(matrix.renderForJudge().contains("2001:db8"));
    }

    @Test
    void moreThanTwelveRowsIsBoundedAndMarksRowLimit() {
        List<RagEvidenceMetadata> rows = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            rows.add(evidence(
                    "W" + i,
                    "title " + i,
                    "https://source" + i + ".example/report",
                    null,
                    i,
                    i,
                    i));
        }
        List<RagEvidenceMetadata> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);

        EnsembleEvidenceMatrix forward = EnsembleEvidenceMatrix.from(rows);
        EnsembleEvidenceMatrix reverse = EnsembleEvidenceMatrix.from(reversed);

        assertEquals(12, forward.rows().size());
        assertEquals(2, forward.droppedRowLimitCount());
        assertTrue(forward.blockers().contains("ROW_LIMIT_APPLIED"));
        assertEquals(forward.matrixId(), reverse.matrixId());
    }

    @Test
    void renderNeverContainsRawTitleLocatorPathQueryOrSecretShapedValues() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(List.of(evidence(
                "marker sk-" + "1234567890abcdef1234",
                "raw confidential title",
                "https://user:password@example.com/report?token=owner-secret#raw-fragment",
                "C:\\private\\customer\\record.txt",
                4,
                7,
                1)));

        String rendered = matrix.renderForJudge();

        assertFalse(rendered.contains("raw confidential title"));
        assertFalse(rendered.contains("example.com"));
        assertFalse(rendered.contains("owner-secret"));
        assertFalse(rendered.contains("customer"));
        assertFalse(rendered.contains("sk-" + "1234567890abcdef1234"));
        assertTrue(rendered.contains("safeMarker=UNKNOWN"));
        assertTrue(rendered.contains("observedAt=UNKNOWN"));
        assertTrue(rendered.contains("independence=UNVERIFIED"));
    }

    @Test
    void sameOriginRowsShareOneProvenanceGroup() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(List.of(
                evidence("W1", "one", "https://same.example/a", null, 1, 2, 1),
                evidence("W2", "two", "https://same.example/b", null, 3, 4, 2)));

        assertEquals(2, matrix.rows().size());
        assertEquals(1, matrix.provenanceGroupCount());
        assertEquals(matrix.rows().get(0).provenanceGroupId(), matrix.rows().get(1).provenanceGroupId());
    }

    @Test
    void differentOriginsRemainIndependenceUnverified() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(List.of(
                evidence("W1", "one", "https://one.example/a", null, 1, 2, 1),
                evidence("W2", "two", "https://two.example/b", null, 3, 4, 2)));

        assertEquals(2, matrix.provenanceGroupCount());
        assertTrue(matrix.rows().stream().allMatch(row -> "UNVERIFIED".equals(row.independence())));
        assertFalse(matrix.supportEligible());
        assertTrue(matrix.blockers().contains("INDEPENDENCE_UNVERIFIED"));
        assertTrue(matrix.containsAllEvidenceIds(
                matrix.evidenceIds().stream().map(String::toUpperCase).toList()));
        assertFalse(matrix.supportsDecisionIds(
                matrix.evidenceIds().stream().toList(),
                "BASE_RATE"));
    }

    @Test
    void missingTimeDirectnessAndRelationBlockSupportedDecisions() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(List.of(evidence(
                "D1", "local", null, "C:\\evidence\\record.txt", 8, 9, 1)));

        assertFalse(matrix.supportEligible());
        assertTrue(matrix.blockers().containsAll(List.of(
                "TIME_UNKNOWN",
                "DIRECTNESS_UNKNOWN",
                "RELATION_UNKNOWN",
                "INDEPENDENCE_UNVERIFIED")));
    }

    @Test
    void missingOrMalformedLocatorFailsClosed() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(List.of(
                evidence("W1", "missing", null, null, null, null, 1),
                evidence("W2", "malformed", "not a public url", null, null, null, 2)));

        assertTrue(matrix.rows().isEmpty());
        assertEquals(2, matrix.droppedMissingLocatorCount());
        assertTrue(matrix.blockers().contains("MISSING_LOCATOR"));
        assertFalse(matrix.supportEligible());
    }

    @Test
    void malformedPublicUrlRecordsRedactedFailureBreadcrumb() {
        String rawSource = "https://exa mple.com/ownerToken=private";

        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(List.of(evidence(
                "W1", "malformed", rawSource, null, null, null, 1)));

        assertTrue(matrix.rows().isEmpty());
        assertEquals(Boolean.TRUE,
                TraceStore.get("ensemble.evidenceMatrix.invalidPublicUrl"));
        assertEquals("invalid_uri",
                TraceStore.get("ensemble.evidenceMatrix.invalidPublicUrl.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawSource));
        assertFalse(trace.contains("ownerToken"));
    }

    @Test
    void matrixSourceHasNoRetrieverModelOrNetworkDependencies() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/ensemble/EnsembleEvidenceMatrix.java"),
                StandardCharsets.UTF_8);

        for (String forbidden : List.of(
                "PromptContext",
                "Retriever",
                "SearchService",
                "WebClient",
                "RestClient",
                "DynamicChatModelFactory")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    private static RagEvidenceMetadata evidence(
            String marker,
            String title,
            String source,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            Integer rank) {
        return new RagEvidenceMetadata(
                marker,
                source == null ? "LOCAL_DOC" : "WEB",
                title,
                source,
                filePath,
                lineStart,
                lineEnd,
                rank,
                0.9d,
                "score");
    }
}
