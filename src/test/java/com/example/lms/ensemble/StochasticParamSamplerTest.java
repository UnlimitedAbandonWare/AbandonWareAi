package com.example.lms.ensemble;

import com.example.lms.infra.selection.SelectionCoordinate;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.infra.selection.SelectionEntropyMode;
import com.example.lms.infra.selection.SelectionReplaySpec;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StochasticParamSamplerTest {

    private static final Set<String> ALLOWED_PARAMS = Set.of(
            "0.20/0.30",
            "0.55/0.60",
            "1.10/0.85",
            "1.40/0.95");

    @AfterEach
    void clearTrace() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void sameReplayRepeatsPillCompositionAcrossFreshSamplers() {
        ReplayDraw first = replayDraw(fixedSeed(13), "trace-a");
        ReplayDraw second = replayDraw(fixedSeed(13), "trace-b");

        assertEquals(first.result(), second.result());
        assertEquals(first.snapshot().decisionDigest(), second.snapshot().decisionDigest());
        assertEquals(first.calls(), second.calls());
        assertEquals(29, first.snapshot().decisionCount());
        assertEquals(29, first.snapshot().ensembleDrawCount());
        for (int index = 0; index < first.calls().size(); index++) {
            EntropyCall call = first.calls().get(index);
            assertEquals("bounded", call.operation());
            assertEquals("ensemble.profile.shuffle", call.coordinate().decisionKey());
            assertEquals("node:opportunistic", call.coordinate().actorKey());
            assertEquals(0L, call.coordinate().attemptOrdinal());
            assertEquals(index, call.coordinate().drawOrdinal());
            assertEquals(30 - index, call.bound());
        }
    }

    @Test
    void injectedDoubleSupplierRemainsTheCompatibilityEntropySourceForShuffle() {
        ArrayDeque<Double> values = new ArrayDeque<>(List.of(
                0.10d, 0.90d, 0.20d, 0.80d, 0.30d, 0.70d,
                0.40d, 0.60d, 0.05d, 0.95d, 0.15d, 0.85d,
                0.25d, 0.75d, 0.35d, 0.65d, 0.45d, 0.55d,
                0.12d, 0.88d, 0.22d, 0.78d, 0.32d, 0.68d,
                0.42d, 0.58d, 0.52d, 0.48d, 0.62d, 0.38d));
        StochasticParamSampler sampler = new StochasticParamSampler(values::removeFirst);

        StochasticParamSampler.DrawResult result = sampler.draw("compatibility-a");

        assertTrue(result.caffeine() >= 0 && result.caffeine() <= 3);
        assertEquals(3 - result.caffeine(), result.theanine());
        assertEquals(1, values.size(), "Fisher-Yates must consume one supplier value per iteration");
    }

    @Test
    void unattachedHolderContextDoesNotReplaceInjectedShuffleEntropy() {
        AtomicInteger calls = new AtomicInteger();
        GuardContextHolder.set(new GuardContext());
        StochasticParamSampler sampler = new StochasticParamSampler(() -> {
            calls.incrementAndGet();
            return 0.5d;
        });

        StochasticParamSampler.DrawResult result = sampler.draw("compatibility-holder");

        assertEquals(3, result.caffeine() + result.theanine());
        assertEquals(29, calls.get(), "an ordinary context must preserve the injected shuffle source");
    }

    @Test
    void unattachedProfileContextDoesNotReplaceInjectedProfileEntropy() {
        AtomicInteger calls = new AtomicInteger();
        GuardContext context = new GuardContext();
        StochasticParamSampler sampler = new StochasticParamSampler(() -> {
            calls.incrementAndGet();
            return 0.5d;
        });

        assertTrue(sampler.applyCreativeProfile(context));

        assertEquals(8, calls.get(), "one selector plus seven profile fields must use the injected source");
    }

    @Test
    void profileFieldsUseDistinctCoordinatesAndRemainWithinExistingBounds() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        RecordingEntropy entropy = new RecordingEntropy(
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(fixedSeed(51))));
        GuardContext context = new GuardContext();
        context.attachSelectionEntropy(entropy, ledger);
        GuardContextHolder.set(context);

        assertTrue(new StochasticParamSampler().applyCreativeProfile(context));

        assertRange((Double) context.getPlanOverride("creative.emergence.candidate.temperature"),
                1.10d, 1.50d);
        assertRange((Double) context.getPlanOverride("creative.emergence.final.topP"),
                0.95d, 1.00d);
        SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(true);
        assertEquals(8, snapshot.decisionCount());
        assertEquals(8, snapshot.drawCount());
        assertEquals(8, snapshot.ensembleDrawCount());
        assertEquals(List.of(
                "ensemble.profile.selector",
                "ensemble.profile.search-temperature",
                "ensemble.profile.search-rate",
                "ensemble.profile.candidate-temperature",
                "ensemble.profile.candidate-top-p",
                "ensemble.profile.final-temperature",
                "ensemble.profile.final-top-p",
                "ensemble.profile.self-ask-temperature"),
                entropy.calls().stream()
                        .map(call -> call.coordinate().decisionKey())
                        .toList());
        assertTrue(entropy.calls().stream().allMatch(call ->
                "unit".equals(call.operation())
                        && "profile:creative-emergence".equals(call.coordinate().actorKey())
                        && call.coordinate().attemptOrdinal() == 0L
                        && call.coordinate().drawOrdinal() == 0L));
    }

    @Test
    void drawReturnsOnlyAllowedPillMappingsAndWritesTrace() {
        StochasticParamSampler sampler = new StochasticParamSampler();

        for (int i = 0; i < 80; i++) {
            String traceId = "rid-" + i;

            StochasticParamSampler.DrawResult result = sampler.draw(traceId);

            assertEquals(3, result.caffeine() + result.theanine());
            String key = String.format(java.util.Locale.ROOT, "%.2f/%.2f",
                    result.temperature(), result.topP());
            assertTrue(ALLOWED_PARAMS.contains(key), "unexpected params: " + key);
            Object trace = TraceStore.get("ensemble.stoch.draw." + SafeRedactor.hash12(traceId));
            assertTrue(String.valueOf(trace).contains("caffeine=" + result.caffeine()));
            assertTrue(String.valueOf(trace).contains("theanine=" + result.theanine()));
            assertTrue(TraceStore.getAll().keySet().stream().noneMatch(keyName -> keyName.contains(traceId)));
        }
    }

    @Test
    void pillCompositionsMapToExactBoundedProfiles() {
        Map<Integer, StochasticParamSampler.DrawResult> expected = Map.of(
                3, new StochasticParamSampler.DrawResult(0.20d, 0.30d, 3, 0),
                2, new StochasticParamSampler.DrawResult(0.55d, 0.60d, 2, 1),
                1, new StochasticParamSampler.DrawResult(1.10d, 0.85d, 1, 2),
                0, new StochasticParamSampler.DrawResult(1.40d, 0.95d, 0, 3));

        for (Map.Entry<Integer, StochasticParamSampler.DrawResult> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), StochasticParamSampler.mapComposition(entry.getKey()));
        }
    }

    @Test
    void selectorBoundariesUseOneSelectorAndSevenIndependentFieldDraws() {
        double[] draws = new double[32];
        Arrays.fill(draws, 0.5d);
        draws[0] = 0.449999d;
        draws[8] = 0.45d;
        draws[16] = 0.849999d;
        draws[24] = 0.85d;
        AtomicInteger index = new AtomicInteger();
        StochasticParamSampler sampler = new StochasticParamSampler(() -> draws[index.getAndIncrement()]);

        assertEquals("VIVID", sampler.drawCreativeProfile().orElseThrow().label());
        assertEquals("WILD", sampler.drawCreativeProfile().orElseThrow().label());
        assertEquals("WILD", sampler.drawCreativeProfile().orElseThrow().label());
        assertEquals("FERAL", sampler.drawCreativeProfile().orElseThrow().label());
        assertEquals(32, index.get(), "each profile must consume one selector and seven field draws");
    }

    @Test
    void creativeProfileCarrierRemainsPackageInternal() {
        assertFalse(java.lang.reflect.Modifier.isPublic(
                StochasticParamSampler.CreativeProfile.class.getModifiers()));
    }

    @Test
    void fixedTwelvePairSequenceStaysInBandsAndProducesDiverseFinalHashes() {
        double[] selectors = {0.00d, 0.10d, 0.20d, 0.30d, 0.449999d, 0.45d,
                0.55d, 0.65d, 0.75d, 0.849999d, 0.85d, 0.99d};
        double[] jitters = {0.00d, 0.11d, 0.22d, 0.33d, 0.44d, 0.55d,
                0.66d, 0.77d, 0.88d, 0.99d, 0.25d, 0.75d};
        double[] sequence = new double[96];
        for (int i = 0; i < selectors.length; i++) {
            sequence[i * 8] = selectors[i];
            Arrays.fill(sequence, i * 8 + 1, i * 8 + 8, jitters[i]);
        }
        AtomicInteger index = new AtomicInteger();
        StochasticParamSampler sampler = new StochasticParamSampler(() -> sequence[index.getAndIncrement()]);
        Set<String> finalHashes = new java.util.HashSet<>();

        for (int i = 0; i < 12; i++) {
            StochasticParamSampler.CreativeProfile profile = sampler.drawCreativeProfile().orElseThrow();
            assertProfileBands(profile);
            assertTwoDecimal(profile.searchTemperature());
            assertTwoDecimal(profile.candidateTemperature());
            assertTwoDecimal(profile.finalTemperature());
            assertTwoDecimal(profile.finalTopP());
            finalHashes.add(SafeRedactor.hashValue(String.format(java.util.Locale.ROOT,
                    "%.2f|%.2f", profile.finalTemperature(), profile.finalTopP())));
        }

        assertEquals(96, index.get());
        assertTrue(finalHashes.size() >= 6, "fixed sequence must yield at least six final option hashes");
    }

    @Test
    void invalidInjectedDrawFailsSoftWithoutLeakingTheValue() {
        AtomicInteger calls = new AtomicInteger();
        StochasticParamSampler sampler = new StochasticParamSampler(
                () -> calls.getAndIncrement() == 0 ? Double.NaN : 0.5d);

        Optional<StochasticParamSampler.CreativeProfile> profile = sampler.drawCreativeProfile();

        assertTrue(profile.isEmpty());
        assertEquals(1, calls.get());
        assertEquals(
                "selection_entropy_derivation_invalid",
                TraceStore.getString("ensemble.stoch.draw.failureReason"));
        assertEquals(1L, TraceStore.getLong("ensemble.stoch.draw.failureCount"));
        assertFalse(TraceStore.getAll().toString().contains("NaN"));
    }

    private static ReplayDraw replayDraw(byte[] seed, String traceId) {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        RecordingEntropy entropy = new RecordingEntropy(
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(seed)));
        GuardContext context = new GuardContext();
        context.attachSelectionEntropy(entropy, ledger);
        GuardContextHolder.set(context);
        try {
            StochasticParamSampler.DrawResult result = new StochasticParamSampler().draw(traceId);
            return new ReplayDraw(result, ledger.snapshot(true), entropy.calls());
        } finally {
            GuardContextHolder.clear();
        }
    }

    private static byte[] fixedSeed(int marker) {
        byte[] seed = new byte[32];
        seed[0] = (byte) marker;
        return seed;
    }

    private record ReplayDraw(
            StochasticParamSampler.DrawResult result,
            SelectionDecisionLedger.Snapshot snapshot,
            List<EntropyCall> calls) {
    }

    private record EntropyCall(
            String operation,
            SelectionCoordinate coordinate,
            int bound) {
    }

    private static final class RecordingEntropy implements SelectionEntropy {
        private final SelectionEntropy delegate;
        private final List<EntropyCall> calls = new ArrayList<>();

        private RecordingEntropy(SelectionEntropy delegate) {
            this.delegate = delegate;
        }

        @Override
        public SelectionEntropyMode mode() {
            return delegate.mode();
        }

        @Override
        public String algorithmVersion() {
            return delegate.algorithmVersion();
        }

        @Override
        public double unitInterval(SelectionCoordinate coordinate) {
            calls.add(new EntropyCall("unit", coordinate, 0));
            return delegate.unitInterval(coordinate);
        }

        @Override
        public int boundedIndex(SelectionCoordinate coordinate, int bound) {
            calls.add(new EntropyCall("bounded", coordinate, bound));
            return delegate.boundedIndex(coordinate, bound);
        }

        private List<EntropyCall> calls() {
            return List.copyOf(calls);
        }
    }

    private static void assertProfileBands(StochasticParamSampler.CreativeProfile p) {
        switch (p.label()) {
            case "VIVID" -> {
                assertRange(p.searchTemperature(), 0.85d, 0.90d);
                assertRange(p.searchExplorationRate(), 0.70d, 0.76d);
                assertRange(p.candidateTemperature(), 1.10d, 1.25d);
                assertRange(p.candidateTopP(), 0.95d, 0.97d);
                assertRange(p.finalTemperature(), 1.05d, 1.20d);
                assertRange(p.finalTopP(), 0.95d, 0.97d);
                assertRange(p.selfAskTemperature(), 0.80d, 0.88d);
            }
            case "WILD" -> {
                assertRange(p.searchTemperature(), 0.91d, 0.97d);
                assertRange(p.searchExplorationRate(), 0.77d, 0.83d);
                assertRange(p.candidateTemperature(), 1.26d, 1.45d);
                assertRange(p.candidateTopP(), 0.97d, 0.99d);
                assertRange(p.finalTemperature(), 1.21d, 1.40d);
                assertRange(p.finalTopP(), 0.97d, 0.99d);
                assertRange(p.selfAskTemperature(), 0.89d, 0.97d);
            }
            case "FERAL" -> {
                assertRange(p.searchTemperature(), 0.98d, 1.00d);
                assertRange(p.searchExplorationRate(), 0.84d, 0.85d);
                assertRange(p.candidateTemperature(), 1.46d, 1.50d);
                assertRange(p.candidateTopP(), 0.99d, 1.00d);
                assertRange(p.finalTemperature(), 1.41d, 1.50d);
                assertRange(p.finalTopP(), 0.99d, 1.00d);
                assertRange(p.selfAskTemperature(), 0.98d, 1.00d);
            }
            default -> throw new AssertionError("unexpected profile " + p.label());
        }
    }

    private static void assertRange(double value, double min, double max) {
        assertTrue(value >= min && value <= max, value + " outside " + min + ".." + max);
    }

    private static void assertTwoDecimal(double value) {
        assertEquals(Math.rint(value * 100.0d), value * 100.0d, 1.0e-9);
    }
}
