package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarmonyScoreEngineTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void subsystemGoalsAddUpToDirectiveGoalPoint() {
        assertEquals(8, SubsystemGoalTable.GOALS.size());
        assertEquals(100.0d, SubsystemGoalTable.totalGoalPoint(), 0.0001d);
    }

    @Test
    void subsystemGoalTablePreservesDashboardOrder() {
        assertEquals(List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08"),
                List.copyOf(SubsystemGoalTable.GOALS.keySet()));
    }

    @Test
    void snapshotBuilderClampsInvalidScoreFieldsToDirectiveRange() {
        HarmonyScoreSnapshot snapshot = HarmonyScoreSnapshot.builder()
                .harmonyScore(Double.NaN)
                .contaminationScore(125.0d)
                .achievementPct(-7.0d)
                .goalPoint(Double.POSITIVE_INFINITY)
                .build();

        assertEquals(0.0d, snapshot.harmonyScore(), 0.0001d);
        assertEquals(100.0d, snapshot.contaminationScore(), 0.0001d);
        assertEquals(0.0d, snapshot.achievementPct(), 0.0001d);
        assertEquals(100.0d, snapshot.goalPoint(), 0.0001d);
    }

    @Test
    void computesPerfectHarmonyWhenAllDirectiveTraceKeysArePresent() {
        TraceStore.put("ablation.penalties", List.of());
        TraceStore.put("retrievalOrder.lastSetBy", "MoE");
        TraceStore.put("routing.executionPlan.primaryMode", "OVERDRIVE");
        TraceStore.put("hypernova.dppApplied", Boolean.TRUE);
        TraceStore.put("hypernova.twpmP", 1.25d);
        TraceStore.put("hypernova.sourceScoreScaleMismatchCount", 0);
        TraceStore.put("extremeZ.cancelShieldWrapped", Boolean.TRUE);
        TraceStore.put("cfvm.boltzmannTemp", 0.72d);
        TraceStore.put("cfvm.tempSource", "CfvmKallocLearningProperties");
        TraceStore.put("cfvm.rawTile.enabled", Boolean.TRUE);
        TraceStore.put("moe.evolverPlateRegistered", Boolean.TRUE);
        TraceStore.put("extremeZ.timeBudgetConsumedMs", 37L);
        TraceStore.put("hypernova.whitening.provider", "ollama");
        TraceStore.put("cihRag.breadcrumb.queryRedacted", Boolean.TRUE);
        TraceStore.put("outCount", 4);

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                new HarmonyBreakLedger(),
                new ContaminationAccumulator())
                .compute();

        assertEquals(100.0d, snapshot.harmonyScore(), 0.0001d);
        assertEquals(0.0d, snapshot.contaminationScore(), 0.0001d);
        assertEquals(100.0d, snapshot.achievementPct(), 0.0001d);
        assertEquals(12, snapshot.harmonyBreaks().size());
        assertTrue(snapshot.harmonyBreaks().stream().allMatch(entry -> "DONE".equals(entry.status())));
    }

    @Test
    void openBreaksSubtractPenaltiesAndContaminationSignalsClampAtHundred() {
        TraceStore.put("ablation.penalties", List.of(
                Map.of("stage", "p01"), Map.of("stage", "p02"), Map.of("stage", "p03"),
                Map.of("stage", "p04"), Map.of("stage", "p05"), Map.of("stage", "p06"),
                Map.of("stage", "p07"), Map.of("stage", "p08"), Map.of("stage", "p09"),
                Map.of("stage", "p10")));
        TraceStore.put("outCount", 0);
        TraceStore.put("starvationFallback.trigger", "all_skipped");
        TraceStore.put("queryTransformer.bypassed", Boolean.TRUE);
        TraceStore.put("extremeZ.cancelShieldWrapped", Boolean.FALSE);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", Boolean.FALSE);
        TraceStore.put("boosterMode.exclusionReason", "conflict");

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                new HarmonyBreakLedger(),
                new ContaminationAccumulator())
                .compute();

        assertEquals(0.0d, snapshot.harmonyScore(), 0.0001d);
        assertEquals(100.0d, snapshot.contaminationScore(), 0.0001d);
        assertTrue(snapshot.harmonyBreaks().stream()
                .anyMatch(entry -> "HB-02".equals(entry.id()) && "BLOCKED_EVIDENCE".equals(entry.status())));
        assertTrue(snapshot.harmonyBreaks().stream()
                .anyMatch(entry -> "HB-07".equals(entry.id()) && "BLOCKED_EVIDENCE".equals(entry.status())));
        assertTrue(snapshot.harmonyBreaks().stream()
                .anyMatch(entry -> "HB-12".equals(entry.id()) && "BLOCKED_EVIDENCE".equals(entry.status())));
        assertTrue(snapshot.topContaminants().contains("starvation_outCount"));
        assertTrue(snapshot.nextGoalHint().contains("HB-01"));
    }

    @Test
    void legacyProxyKeysCannotCompleteCanonicalBreaks() {
        TraceStore.put("boosterMode.active", "OVERDRIVE");
        TraceStore.put("hypernova.cvarPhi", 0.618d);
        TraceStore.put("ablation.score.current", 0.0d);
        TraceStore.put("cfvm.tempAnnealApplied", Boolean.TRUE);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", Boolean.TRUE);
        TraceStore.put("outCount", 4);

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                new HarmonyBreakLedger(),
                new ContaminationAccumulator())
                .compute();

        for (String id : List.of("HB-03", "HB-05", "HB-06", "HB-09", "HB-12")) {
            assertTrue(snapshot.harmonyBreaks().stream().anyMatch(entry -> id.equals(entry.id())
                            && "BLOCKED_EVIDENCE".equals(entry.status())),
                    "legacy proxy evidence must not complete canonical " + id);
        }
    }

    @Test
    void canonicalBreaksPenalizeTheirOwningSubsystems() {
        assertMissingEvidencePenalizes("retrievalOrder.lastSetBy", "HB-02", "S02", "S03");
        assertMissingEvidencePenalizes("routing.executionPlan.primaryMode", "HB-03", "S03", "S05");
        assertMissingEvidencePenalizes("hypernova.dppApplied", "HB-04", "S06", "S04");
        assertMissingEvidencePenalizes("cfvm.tempSource", "HB-08", "S02", "S08");
        assertMissingEvidencePenalizes("hypernova.whitening.provider", "HB-12", "S04", "S07");
    }

    @Test
    void nonEmptyPenaltiesAndPositiveScaleMismatchRemainOpen() {
        Map<String, Object> evidence = allHarmonyEvidence();
        evidence.put("ablation.penalties", List.of(Map.of("stage", "p01")));
        evidence.put("hypernova.sourceScoreScaleMismatchCount", 2);
        HarmonyTraceReader reader = traceReaderWithSnapshot(evidence);

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                new HarmonyBreakLedger(reader),
                new ContaminationAccumulator(reader),
                reader)
                .compute();

        assertTrue(snapshot.harmonyBreaks().stream().anyMatch(entry -> "HB-01".equals(entry.id())
                && "BLOCKED_EVIDENCE".equals(entry.status())));
        assertTrue(snapshot.harmonyBreaks().stream().anyMatch(entry -> "HB-06".equals(entry.id())
                && "BLOCKED_EVIDENCE".equals(entry.status())));
    }

    @Test
    void placeholderAuthorityLabelsCannotCompleteCanonicalBreaks() {
        Map<String, Object> evidence = allHarmonyEvidence();
        evidence.put("retrievalOrder.lastSetBy", "unknown");
        evidence.put("routing.executionPlan.primaryMode", "hash:placeholder");
        evidence.put("cfvm.tempSource", "(redacted)");
        evidence.put("hypernova.whitening.provider", "unknown");
        HarmonyTraceReader reader = traceReaderWithSnapshot(evidence);

        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = new HarmonyBreakLedger(reader).evaluate();

        for (String id : List.of("HB-02", "HB-03", "HB-08", "HB-12")) {
            assertTrue(breaks.stream().anyMatch(entry -> id.equals(entry.id())
                            && "BLOCKED_EVIDENCE".equals(entry.status())),
                    "placeholder authority label must not complete " + id);
        }
    }

    @Test
    void freeFormAuthorityLabelsCannotCompleteCanonicalBreaks() {
        Map<String, Object> evidence = allHarmonyEvidence();
        String raw = "private student question with arbitrary owner text";
        evidence.put("retrievalOrder.lastSetBy", raw);
        evidence.put("routing.executionPlan.primaryMode", raw);
        evidence.put("cfvm.tempSource", raw);
        evidence.put("hypernova.whitening.provider", raw);
        HarmonyTraceReader reader = traceReaderWithSnapshot(evidence);

        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = new HarmonyBreakLedger(reader).evaluate();

        for (String id : List.of("HB-02", "HB-03", "HB-08", "HB-12")) {
            assertTrue(breaks.stream().anyMatch(entry -> id.equals(entry.id())
                            && "BLOCKED_EVIDENCE".equals(entry.status())),
                    "free-form authority text must not complete " + id);
        }
    }

    @Test
    void breakLedgerReadsRetrievalOrderServiceTraceFromRecentSnapshot() {
        new RetrievalOrderService().decideOrder("RAG evidence starvation debug");
        Object lastSetBy = TraceStore.get("retrievalOrder.lastSetBy");
        Object lastOrder = TraceStore.get("retrievalOrder.lastOrder");
        TraceStore.clear();

        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        TraceSnapshotStore.TraceSnapshot snapshot = new TraceSnapshotStore.TraceSnapshot(
                "snapshot-1",
                1L,
                "2026-06-20T00:00:00Z",
                "sidHash",
                "sessionHash",
                "traceHash",
                "requestHash",
                "http_request",
                "POST",
                "/api/chat",
                200,
                null,
                true,
                2,
                Map.of(),
                Map.of(
                        "retrievalOrder.lastSetBy", lastSetBy,
                        "retrievalOrder.lastOrder", lastOrder),
                Map.of(),
                null,
                false);
        when(store.listSummaries(20)).thenReturn(List.of(Map.of("id", "snapshot-1")));
        when(store.get("snapshot-1")).thenReturn(Optional.of(snapshot));

        HarmonyBreakLedger ledger = new HarmonyBreakLedger(mockProvider(store));

        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = ledger.evaluate();

        assertTrue(breaks.stream()
                .anyMatch(entry -> "HB-02".equals(entry.id())
                        && "DONE".equals(entry.status())
                        && entry.evidence().contains("recentSnapshot")));
    }

    @Test
    void readerSkipsNewestIrrelevantSnapshotButDoesNotBackfillPastLatestEligibleRuntimeFrame() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        TraceSnapshotStore.TraceSnapshot irrelevant = traceSnapshot(
                "snapshot-irrelevant",
                "/api/diagnostics/trace/snapshots",
                Map.of("outCount", 0));
        TraceSnapshotStore.TraceSnapshot latestRuntime = traceSnapshot(
                "snapshot-runtime-latest",
                "/api/chat",
                Map.of("retrievalOrder.lastSetBy", "MoE"));
        TraceSnapshotStore.TraceSnapshot olderRuntime = traceSnapshot(
                "snapshot-runtime-older",
                "/api/chat",
                Map.of("boosterMode.active", "OVERDRIVE"));
        when(store.listSummaries(20)).thenReturn(List.of(
                Map.of("id", irrelevant.id()),
                Map.of("id", latestRuntime.id()),
                Map.of("id", olderRuntime.id())));
        when(store.get(irrelevant.id())).thenReturn(Optional.of(irrelevant));
        when(store.get(latestRuntime.id())).thenReturn(Optional.of(latestRuntime));
        when(store.get(olderRuntime.id())).thenReturn(Optional.of(olderRuntime));

        HarmonyBreakLedger ledger = new HarmonyBreakLedger(
                new HarmonyTraceReader(mockProvider(store)));

        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = ledger.evaluate();

        assertTrue(breaks.stream().anyMatch(entry -> "HB-02".equals(entry.id())
                && "DONE".equals(entry.status())));
        assertTrue(breaks.stream().anyMatch(entry -> "HB-03".equals(entry.id())
                && "BLOCKED_EVIDENCE".equals(entry.status())),
                "a coherent score frame must not backfill booster evidence from an older request");
    }

    @Test
    void synergyBonusRequiresSignalsFromSameEvidenceFrame() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        Map<String, Object> latestEvidence = allHarmonyEvidence();
        latestEvidence.remove("extremeZ.cancelShieldWrapped");
        TraceSnapshotStore.TraceSnapshot latestRuntime = traceSnapshot(
                "snapshot-runtime-latest",
                "/api/chat",
                latestEvidence);
        TraceSnapshotStore.TraceSnapshot olderRuntime = traceSnapshot(
                "snapshot-runtime-older",
                "/api/chat",
                Map.of("extremeZ.cancelShieldWrapped", Boolean.TRUE));
        when(store.listSummaries(20)).thenReturn(List.of(
                Map.of("id", latestRuntime.id()),
                Map.of("id", olderRuntime.id())));
        when(store.get(latestRuntime.id())).thenReturn(Optional.of(latestRuntime));
        when(store.get(olderRuntime.id())).thenReturn(Optional.of(olderRuntime));
        HarmonyTraceReader reader = new HarmonyTraceReader(mockProvider(store));

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                new HarmonyBreakLedger(reader),
                new ContaminationAccumulator(reader),
                reader)
                .compute();

        assertEquals(0.0d, snapshot.harmonyScore(), 0.0001d,
                "missing same-frame evidence must block promotion instead of granting partial credit");
    }

    @Test
    void contaminationScoreAndTopContaminantsUseSameEvidenceFrame() {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        TraceSnapshotStore.TraceSnapshot latestRuntime = traceSnapshot(
                "snapshot-runtime-latest",
                "/api/chat",
                Map.of("outCount", 0));
        TraceSnapshotStore.TraceSnapshot olderRuntime = traceSnapshot(
                "snapshot-runtime-older",
                "/api/chat",
                Map.of(
                        "starvationFallback.trigger", "all_skipped",
                        "queryTransformer.bypassed", Boolean.TRUE));
        when(store.listSummaries(20)).thenReturn(List.of(
                Map.of("id", latestRuntime.id()),
                Map.of("id", olderRuntime.id())));
        when(store.get(latestRuntime.id())).thenReturn(Optional.of(latestRuntime));
        when(store.get(olderRuntime.id())).thenReturn(Optional.of(olderRuntime));
        HarmonyTraceReader reader = new HarmonyTraceReader(mockProvider(store));
        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);
        HarmonyTraceReader.TraceFrame frame = reader.readFrame();

        assertEquals(20.0d, accumulator.compute(frame), 0.0001d);
        assertEquals(List.of("starvation_outCount"), accumulator.topContaminants(3, frame));
    }

    @Test
    void breakLedgerRejectsSanitizedSummaryObjectAsDoneEvidence() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "hypernova.twpmP", Map.of(
                        "present", Boolean.TRUE,
                        "len", 3,
                        "hash12", "redactedhash")));

        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = new HarmonyBreakLedger(reader).evaluate();

        assertTrue(breaks.stream().anyMatch(entry -> "HB-05".equals(entry.id())
                && "BLOCKED_EVIDENCE".equals(entry.status())),
                "a redacted summary object is provenance metadata, not typed numeric proof");
    }

    @Test
    void synergyRejectsSanitizedSummaryObject() {
        Map<String, Object> evidence = allHarmonyEvidence();
        evidence.put("hypernova.twpmP", Map.of(
                "present", Boolean.TRUE,
                "len", 3,
                "hash12", "redactedhash"));
        HarmonyTraceReader reader = traceReaderWithSnapshot(evidence);

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                new HarmonyBreakLedger(reader),
                new ContaminationAccumulator(reader),
                reader)
                .compute();

        assertEquals(0.0d, snapshot.harmonyScore(), 0.0001d,
                "hash-only summary metadata must block promotion");
    }

    @Test
    void contaminationRejectsSanitizedSummaryObject() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "outCount", 4,
                "queryTransformer.bypassed", Map.of(
                        "present", Boolean.TRUE,
                        "len", 4,
                        "hash12", "redactedhash")));
        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);

        assertEquals(0.0d, accumulator.compute(), 0.0001d);
        assertTrue(accumulator.topContaminants(3).isEmpty());
    }

    @Test
    void breakLedgerTraceReadFailureLeavesRedactedBreadcrumb() {
        HarmonyBreakLedger ledger = new HarmonyBreakLedger(new HarmonyTraceReader() {
            @Override
            public TraceFrame readFrame() {
                throw new IllegalStateException("raw-secret-message");
            }
        });

        List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks = ledger.evaluate();

        assertTrue(breaks.stream()
                .anyMatch(entry -> "BLOCKED_EVIDENCE".equals(entry.status())));
        assertEquals(Boolean.TRUE, TraceStore.get("harmony.breakLedger.traceRead.failed"));
        assertEquals("IllegalStateException", TraceStore.get("harmony.breakLedger.traceRead.errorType"));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains("raw-secret-message"));
    }

    @Test
    void contaminationAccumulatorReadsRecentSnapshotWhenCurrentRequestTraceIsMissing() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "outCount", 0,
                "starvationFallback.trigger", "all_skipped",
                "queryTransformer.bypassed", Boolean.TRUE));
        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);

        assertEquals(45.0d, accumulator.compute(), 0.0001d);
        assertEquals(List.of("starvation_outCount", "provider_all_skipped", "qt_bypassed"),
                accumulator.topContaminants(3));
    }

    @Test
    void singlePrimaryModeExclusionReasonIsNotBoosterContamination() {
        TraceStore.put("boosterMode.exclusionReason", "single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE");

        ContaminationAccumulator accumulator = new ContaminationAccumulator();

        assertEquals(0.0d, accumulator.compute(), 0.0001d);
        assertTrue(accumulator.topContaminants(3).isEmpty());
    }

    @Test
    void resolvedBoosterConflictSnapshotDoesNotCountAsContaminationAfterRedaction() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "boosterMode.exclusionReason", "hash:resolved-single-primary",
                "boosterMode.conflictResolved", Boolean.TRUE,
                "boosterMode.excludedModes", List.of("OVERDRIVE", "HYPERNOVA")));

        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);

        assertEquals(0.0d, accumulator.compute(), 0.0001d);
        assertTrue(accumulator.topContaminants(3).isEmpty());
    }

    @Test
    void normalPlanSnapshotDoesNotCountHashedExclusionReasonAsContamination() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "boosterMode.exclusionReason", "hash:normal-single-primary",
                "boosterMode.conflictResolved", Boolean.FALSE,
                "boosterMode.excludedModes", List.of()));

        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);

        assertEquals(0.0d, accumulator.compute(), 0.0001d);
        assertTrue(accumulator.topContaminants(3).isEmpty());
    }

    @Test
    void unresolvedBoosterConflictSnapshotCountsAsContaminationWhenResolutionIsFalse() {
        HarmonyTraceReader reader = traceReaderWithSnapshot(Map.of(
                "boosterMode.exclusionReason", "hash:unresolved-conflict",
                "boosterMode.conflictResolved", Boolean.FALSE,
                "boosterMode.excludedModes", List.of("OVERDRIVE")));

        ContaminationAccumulator accumulator = new ContaminationAccumulator(reader);

        assertEquals(10.0d, accumulator.compute(), 0.0001d);
        assertEquals(List.of("booster_conflict"), accumulator.topContaminants(3));
    }

    @Test
    void computeFailureLeavesTraceBreadcrumb() {
        HarmonyScoreEngine engine = new HarmonyScoreEngine(new HarmonyBreakLedger() {
            @Override
            List<HarmonyScoreSnapshot.HarmonyBreakEntry> evaluate(HarmonyTraceReader.TraceFrame frame) {
                throw new RuntimeException("synthetic failure");
            }
        }, new ContaminationAccumulator());

        HarmonyScoreSnapshot snapshot = engine.compute();

        assertEquals(0.0d, snapshot.harmonyScore(), 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("harmony.score.compute.failed"));
        assertEquals("RuntimeException", TraceStore.get("harmony.score.compute.errorType"));
    }

    @Test
    void blockedEvidenceFailsClosedInsteadOfReportingPartialHarmony() {
        HarmonyBreakLedger ledger = new HarmonyBreakLedger() {
            @Override
            List<HarmonyScoreSnapshot.HarmonyBreakEntry> evaluate(HarmonyTraceReader.TraceFrame frame) {
                return List.of(new HarmonyScoreSnapshot.HarmonyBreakEntry(
                        "HB-01",
                        "BLOCKED_EVIDENCE",
                        35.6d,
                        "evidence_needed: synthetic trace read failure"));
            }
        };

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                ledger,
                new ContaminationAccumulator())
                .compute();

        assertEquals(0.0d, snapshot.harmonyScore(), 0.0001d);
        assertTrue(snapshot.nextGoalHint().contains("HB-01"));
        assertTrue(snapshot.subsystemScores().get("S01").current()
                < snapshot.subsystemScores().get("S01").goal());
    }

    private static HarmonyTraceReader traceReaderWithSnapshot(Map<String, Object> trace) {
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        TraceSnapshotStore.TraceSnapshot snapshot = traceSnapshot("snapshot-1", "/api/chat", trace);
        when(store.listSummaries(20)).thenReturn(List.of(Map.of("id", "snapshot-1")));
        when(store.get("snapshot-1")).thenReturn(Optional.of(snapshot));
        return new HarmonyTraceReader(mockProvider(store));
    }

    private static TraceSnapshotStore.TraceSnapshot traceSnapshot(
            String id,
            String path,
            Map<String, Object> trace) {
        return new TraceSnapshotStore.TraceSnapshot(
                id,
                1L,
                "2026-06-20T00:00:00Z",
                "sidHash",
                "sessionHash",
                "traceHash",
                "requestHash",
                "http_request",
                "POST",
                path,
                200,
                null,
                true,
                trace.size(),
                Map.of(),
                trace,
                Map.of(),
                null,
                false);
    }

    private static Map<String, Object> allHarmonyEvidence() {
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("ablation.penalties", List.of());
        evidence.put("retrievalOrder.lastSetBy", "MoE");
        evidence.put("routing.executionPlan.primaryMode", "OVERDRIVE");
        evidence.put("hypernova.dppApplied", Boolean.TRUE);
        evidence.put("hypernova.twpmP", 1.25d);
        evidence.put("hypernova.sourceScoreScaleMismatchCount", 0);
        evidence.put("extremeZ.cancelShieldWrapped", Boolean.TRUE);
        evidence.put("cfvm.boltzmannTemp", 0.72d);
        evidence.put("cfvm.tempSource", "CfvmKallocLearningProperties");
        evidence.put("cfvm.rawTile.enabled", Boolean.TRUE);
        evidence.put("moe.evolverPlateRegistered", Boolean.TRUE);
        evidence.put("extremeZ.timeBudgetConsumedMs", 37L);
        evidence.put("hypernova.whitening.provider", "ollama");
        evidence.put("cihRag.breadcrumb.queryRedacted", Boolean.TRUE);
        evidence.put("outCount", 4);
        return evidence;
    }

    private static void assertMissingEvidencePenalizes(
            String missingKey,
            String breakId,
            String expectedSubsystem,
            String unaffectedSubsystem) {
        Map<String, Object> evidence = allHarmonyEvidence();
        evidence.remove(missingKey);
        HarmonyTraceReader reader = traceReaderWithSnapshot(evidence);

        HarmonyScoreSnapshot snapshot = new HarmonyScoreEngine(
                new HarmonyBreakLedger(reader),
                new ContaminationAccumulator(reader),
                reader)
                .compute();

        assertTrue(snapshot.harmonyBreaks().stream().anyMatch(entry -> breakId.equals(entry.id())
                        && "BLOCKED_EVIDENCE".equals(entry.status())),
                missingKey + " must leave " + breakId + " BLOCKED_EVIDENCE");
        HarmonyScoreSnapshot.SubsystemScore owner = snapshot.subsystemScores().get(expectedSubsystem);
        HarmonyScoreSnapshot.SubsystemScore unaffected = snapshot.subsystemScores().get(unaffectedSubsystem);
        assertTrue(owner.current() < owner.goal(), breakId + " must penalize " + expectedSubsystem);
        assertEquals(unaffected.goal(), unaffected.current(), 0.0001d,
                breakId + " must not penalize " + unaffectedSubsystem);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TraceSnapshotStore> mockProvider(TraceSnapshotStore store) {
        ObjectProvider<TraceSnapshotStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }
}
