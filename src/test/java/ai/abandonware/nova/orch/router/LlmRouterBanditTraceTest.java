package ai.abandonware.nova.orch.router;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.infra.selection.SelectionCoordinate;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyException;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.infra.selection.SelectionEntropyMode;
import com.example.lms.infra.selection.SelectionEntropyReason;
import com.example.lms.infra.selection.SelectionReplaySpec;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRouterBanditTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void directPickAndOutcomePublishCihRagRouterTrace() {
        LlmRouterBandit bandit = new LlmRouterBandit(props("gemma"));

        LlmRouterBandit.Selected selected = bandit.pick("llmrouter.gemma");

        assertEquals("gemma", selected.key());
        assertEquals("gemma", TraceStore.get("cihRag.routedModel"));
        assertEquals("gemma", TraceStore.get("llm.router.selected"));
        assertEquals("direct", TraceStore.get("llm.router.mode"));
        assertEquals("", TraceStore.get("llm.router.skipReason"));
        assertEquals(-1, TraceStore.get("cihRag.ucb1Reward"));

        bandit.recordOutcome("gemma", true, 42L);
        assertEquals(1, TraceStore.get("cihRag.ucb1Reward"));
        assertEquals(1.0d, (Double) TraceStore.get("llm.router.rewardSignal"), 1.0e-9d);

        bandit.recordOutcome("gemma", false, 42L);
        assertEquals(0, TraceStore.get("cihRag.ucb1Reward"));
        assertEquals(0.0d, (Double) TraceStore.get("llm.router.rewardSignal"), 1.0e-9d);
    }

    @Test
    void autoColdStartPublishesExploreAliasTrace() {
        LlmRouterBandit bandit = new LlmRouterBandit(props("alpha", "beta"));

        LlmRouterBandit.Selected selected = bandit.pick("llmrouter.auto");

        assertNotNull(selected);
        assertEquals(selected.key(), TraceStore.get("llm.router.selected"));
        assertEquals("explore", TraceStore.get("llm.router.mode"));
        assertEquals(0.0d, (Double) TraceStore.get("llm.router.ucb1.score"), 1.0e-9d);
        assertEquals("", TraceStore.get("llm.router.skipReason"));
    }

    @Test
    void autoPickPublishesUcb1ArmScoreAndExplorationTrace() {
        LlmRouterBandit bandit = new LlmRouterBandit(props("alpha", "beta"));
        bandit.recordOutcome("alpha", true, 25L);
        bandit.recordOutcome("beta", false, 40L);
        TraceStore.clear();

        LlmRouterBandit.Selected selected = bandit.pick("llmrouter.auto");

        assertNotNull(selected);
        assertEquals(selected.key(), TraceStore.get("llm.router.arm"));
        assertEquals(selected.key(), TraceStore.get("llm.router.selected"));
        assertEquals("exploit", TraceStore.get("llm.router.mode"));
        assertEquals("ucb1", TraceStore.get("llm.router.policy"));
        assertEquals(1L, TraceStore.get("llm.router.arm.sampleCount"));
        assertNotNull(TraceStore.get("llm.router.ucbScore"));
        assertNotNull(TraceStore.get("llm.router.ucb1.score"));
        assertNotNull(TraceStore.get("llm.router.arm.explorationBonus"));
        assertEquals("", TraceStore.get("llm.router.skipReason"));
    }

    @Test
    void autoPickPublishesSkipReasonWhenAllCandidatesAreIneligible() {
        LlmRouterBandit bandit = new LlmRouterBandit(props("alpha", "beta"));

        LlmRouterBandit.Selected selected = bandit.pick("llmrouter.auto", (key, cfg) -> false);

        assertNull(selected);
        assertEquals("skipped", TraceStore.get("llm.router.mode"));
        assertEquals(0.0d, (Double) TraceStore.get("llm.router.ucb1.score"), 1.0e-9d);
        assertEquals("no_eligible_models", TraceStore.get("llm.router.skipReason"));
    }

    @Test
    void hashMapInsertionOrderDoesNotChangeColdStartOrExactUcbTie() {
        LlmRouterBandit first = banditWithModels("route-b", "route-a");
        LlmRouterBandit second = banditWithModels("route-a", "route-b");

        assertEquals("route-a", first.pick("llmrouter.auto").key());
        assertEquals("route-a", second.pick("llmrouter.auto").key());

        seedEqualArmHistory(first, "route-a", "route-b");
        seedEqualArmHistory(second, "route-a", "route-b");
        assertEquals(
                first.pick("llmrouter.auto").key(),
                second.pick("llmrouter.auto").key());
    }

    @Test
    void canonicalColdStartTieRecordsNoEntropyDraw() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();

        LlmRouterBandit.Selected selected = withReplay(
                new byte[32],
                ledger,
                () -> banditWithModels("route-b", "route-a").pick("llmrouter.auto"));

        SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(false);
        assertEquals("route-a", selected.key());
        assertEquals(1, snapshot.decisionCount());
        assertEquals(1, snapshot.stableTieBreakCount());
        assertEquals(0, snapshot.drawCount());
    }

    @Test
    void sameReplayAndFixedSeedDeckMakeWeightedSelectionRepeatAndExplore() {
        Set<String> selected = new LinkedHashSet<>();
        for (int seedMarker : List.of(0, 17, 41, 93)) {
            byte[] seed = new byte[32];
            seed[0] = (byte) seedMarker;
            String first = withReplay(seed, () -> weightedChoice(weightedBandit()));
            String second = withReplay(seed, () -> weightedChoice(weightedBandit()));
            selected.add(first);
            assertEquals(first, second);
        }
        assertTrue(selected.size() > 1);
    }

    @Test
    void replayDerivationFailureFromForcedWeightedFallbackIsNotFailSoft() {
        AtomicInteger weightReads = new AtomicInteger();
        LlmRouterProperties.ModelConfig config = new LlmRouterProperties.ModelConfig() {
            @Override
            public double getWeight() {
                return weightReads.incrementAndGet() == 2 ? Double.NaN : 1.0d;
            }
        };
        config.setEnabled(true);
        config.setName("weighted-model");
        config.setProvider("local");
        LlmRouterProperties properties = new LlmRouterProperties();
        properties.setEnabled(true);
        properties.setModels(Map.of("weighted", config));
        LlmRouterBandit bandit = new LlmRouterBandit(properties);
        bandit.recordOutcome("weighted", true, 25L);
        SelectionEntropy rejectingEntropy = new SelectionEntropy() {
            @Override
            public SelectionEntropyMode mode() {
                return SelectionEntropyMode.REPLAY;
            }

            @Override
            public String algorithmVersion() {
                return "v1";
            }

            @Override
            public double unitInterval(SelectionCoordinate coordinate) {
                throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
            }

            @Override
            public int boundedIndex(SelectionCoordinate coordinate, int bound) {
                throw new AssertionError("weighted router must use unitInterval");
            }
        };
        GuardContext context = GuardContext.defaultContext();
        context.attachSelectionEntropy(rejectingEntropy, SelectionDecisionLedger.forReplay());
        GuardContextHolder.set(context);

        SelectionEntropyException failure = assertThrows(
                SelectionEntropyException.class,
                () -> bandit.pick("llmrouter.auto"));

        assertEquals(SelectionEntropyReason.DERIVATION_INVALID, failure.reason());
        assertTrue(weightReads.get() >= 3, "test must reach the real weighted fallback");
    }

    private static LlmRouterProperties props(String key) {
        return props(new String[]{key});
    }

    private static LlmRouterProperties props(String... keys) {
        LlmRouterProperties props = new LlmRouterProperties();
        Map<String, LlmRouterProperties.ModelConfig> models = new LinkedHashMap<>();
        for (String key : keys) {
            LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
            cfg.setEnabled(true);
            cfg.setName("model-" + key);
            cfg.setBaseUrl("http://localhost:11434/v1");
            cfg.setWeight(1.0d);
            models.put(key, cfg);
        }
        props.setEnabled(true);
        props.setModels(models);
        return props;
    }

    private static LlmRouterBandit banditWithModels(String... keys) {
        LlmRouterProperties properties = new LlmRouterProperties();
        Map<String, LlmRouterProperties.ModelConfig> models = new LinkedHashMap<>();
        for (String key : keys) {
            LlmRouterProperties.ModelConfig config = new LlmRouterProperties.ModelConfig();
            config.setEnabled(true);
            config.setName(key);
            config.setProvider("local");
            config.setWeight(1.0d);
            models.put(key, config);
        }
        properties.setEnabled(true);
        properties.setModels(models);
        return new LlmRouterBandit(properties);
    }

    private static void seedEqualArmHistory(LlmRouterBandit bandit, String... keys) {
        for (String key : keys) {
            bandit.recordOutcome(key, true, 25L);
        }
    }

    private static LlmRouterBandit weightedBandit() {
        return banditWithModels("route-a", "route-b", "route-c");
    }

    private static String weightedChoice(LlmRouterBandit bandit) {
        List<LlmRouterBandit.Candidate> candidates = bandit.candidatesForTest();
        return bandit.pickWeightedRandom(candidates, "route:primary", 0L).key();
    }

    private static <T> T withReplay(byte[] seed, Supplier<T> action) {
        return withReplay(seed, SelectionDecisionLedger.forReplay(), action);
    }

    private static <T> T withReplay(
            byte[] seed,
            SelectionDecisionLedger ledger,
            Supplier<T> action) {
        GuardContext context = GuardContext.defaultContext();
        context.attachSelectionEntropy(
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(seed)),
                ledger);
        GuardContextHolder.set(context);
        try {
            return action.get();
        } finally {
            GuardContextHolder.clear();
        }
    }
}
