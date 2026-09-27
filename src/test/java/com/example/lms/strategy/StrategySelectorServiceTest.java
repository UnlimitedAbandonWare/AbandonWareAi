package com.example.lms.strategy;

import com.example.lms.infra.selection.SelectionCoordinate;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.infra.selection.SelectionEntropyMode;
import com.example.lms.infra.selection.SelectionReplaySpec;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.strategy.StrategyPerformanceRepository.StatsRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategySelectorServiceTest {

    private static final SelectionCoordinate SOFTMAX_COORDINATE =
            new SelectionCoordinate("strategy.softmax", "strategy:dynamic", 0L, 0L);
    private static final SelectionCoordinate EPSILON_BRANCH_COORDINATE =
            new SelectionCoordinate("strategy.epsilon-branch", "strategy:dynamic", 0L, 0L);
    private static final SelectionCoordinate EPSILON_INDEX_COORDINATE =
            new SelectionCoordinate("strategy.epsilon-index", "strategy:dynamic", 0L, 0L);

    @AfterEach
    void clearGuardContext() {
        GuardContextHolder.clear();
    }

    @Test
    void sameReplayRepeatsSoftmaxAndEpsilonAcrossRowPermutations() {
        StatsRow web = row("WEB_FIRST", 8L, 2L, 0.70d);
        StatsRow vector = row("VECTOR_FIRST", 6L, 4L, 0.55d);
        StatsRow fusion = row("WEB_VECTOR_FUSION", 7L, 3L, 0.60d);

        SelectionRun first = select(List.of(web, vector, fusion), 1.0d);
        SelectionRun permuted = select(List.of(fusion, web, vector), 1.0d);

        assertThat(permuted.selected()).isEqualTo(first.selected());
        assertThat(permuted.snapshot().decisionDigest()).isEqualTo(first.snapshot().decisionDigest());
        assertThat(permuted.calls()).isEqualTo(first.calls());
        assertThat(first.snapshot().drawCount()).isEqualTo(3);
    }

    @Test
    void invalidNonFiniteStatsReturnComplexityBaseWithoutDraw() {
        SelectionRun run = select(List.of(
                row("WEB_FIRST", 8L, 2L, Double.NaN),
                row("not-a-strategy", 1L, 0L, 1.0d)), 1.0d);

        assertThat(run.selected()).isEqualTo(StrategySelectorService.Strategy.DEEP_DIVE_SELF_ASK);
        assertThat(run.calls()).isEmpty();
        assertThat(run.snapshot().decisionCount()).isZero();
        assertThat(run.snapshot().drawCount()).isZero();
        assertThat(run.snapshot().strategyDrawCount()).isZero();
    }

    @Test
    void epsilonZeroConsumesOnlySoftmaxDraw() {
        SelectionRun run = select(validRows(), 0.0d);

        assertThat(run.calls()).containsExactly(new EntropyCall("unit", SOFTMAX_COORDINATE, 0));
        assertThat(run.snapshot().decisionCount()).isEqualTo(1);
        assertThat(run.snapshot().drawCount()).isEqualTo(1);
        assertThat(run.snapshot().strategyDrawCount()).isEqualTo(1);
    }

    @Test
    void epsilonOneUsesSeparateBranchAndIndexCoordinates() {
        SelectionRun run = select(validRows(), 1.0d);

        assertThat(run.calls()).containsExactly(
                new EntropyCall("unit", SOFTMAX_COORDINATE, 0),
                new EntropyCall("unit", EPSILON_BRANCH_COORDINATE, 0),
                new EntropyCall("bounded", EPSILON_INDEX_COORDINATE,
                        StrategySelectorService.Strategy.values().length));
        assertThat(run.snapshot().decisionCount()).isEqualTo(3);
        assertThat(run.snapshot().drawCount()).isEqualTo(3);
        assertThat(run.snapshot().strategyDrawCount()).isEqualTo(3);
    }

    @Test
    void invalidProbabilityMassUsesFirstCanonicalStrategyWithoutDraw() {
        SelectionRun run = select(validRows(), 1.0d, Double.NaN);

        assertThat(run.selected()).isEqualTo(StrategySelectorService.Strategy.VECTOR_FIRST);
        assertThat(run.calls()).isEmpty();
        assertThat(run.snapshot().decisionCount()).isEqualTo(1);
        assertThat(run.snapshot().drawCount()).isZero();
        assertThat(run.snapshot().strategyDrawCount()).isZero();
        assertThat(run.snapshot().stableTieBreakCount()).isEqualTo(1);
        assertThat(run.snapshot().decisionDigest()).isEqualTo(
                "a0af1944999751e91357e5477f2f4d16b245dea79c5e471386fdcb4fc9df7ded");
    }

    private static SelectionRun select(List<StatsRow> rows, double epsilon) {
        return select(rows, epsilon, 1.0d);
    }

    private static SelectionRun select(List<StatsRow> rows, double epsilon, double temperature) {
        QueryComplexityGate gate = mock(QueryComplexityGate.class);
        when(gate.assess("complex question")).thenReturn(QueryComplexityGate.Level.COMPLEX);

        StrategyPerformanceRepository repository = mock(StrategyPerformanceRepository.class);
        when(repository.findStatsByCategory("default")).thenReturn(rows);

        StrategyHyperparams hyperparams = mock(StrategyHyperparams.class);
        when(hyperparams.temperature()).thenReturn(1.0d);
        when(hyperparams.epsilon()).thenReturn(epsilon);

        HyperparameterService dynamic = mock(HyperparameterService.class);
        when(dynamic.getDouble("strategy.prior.base", 0.10d)).thenReturn(0.10d);
        when(dynamic.getDouble("strategy.weight.success_rate", 0.65d)).thenReturn(0.65d);
        when(dynamic.getDouble("strategy.weight.reward", 0.30d)).thenReturn(0.30d);
        when(dynamic.getPositiveDouble("strategy.temperature", 1.0d)).thenReturn(temperature);
        when(dynamic.getDoubleInRange01("strategy.epsilon", epsilon)).thenReturn(epsilon);

        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        RecordingEntropy entropy = new RecordingEntropy(
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(fixedSeed())));
        GuardContext context = new GuardContext();
        context.attachSelectionEntropy(entropy, ledger);
        GuardContextHolder.set(context);

        try {
            StrategySelectorService service = new StrategySelectorService(
                    gate, repository, hyperparams, dynamic);
            StrategySelectorService.Strategy selected =
                    service.selectForQuestion("complex question", null);
            return new SelectionRun(selected, ledger.snapshot(true), entropy.calls());
        } finally {
            GuardContextHolder.clear();
        }
    }

    private static List<StatsRow> validRows() {
        return List.of(
                row("WEB_FIRST", 8L, 2L, 0.70d),
                row("VECTOR_FIRST", 6L, 4L, 0.55d),
                row("WEB_VECTOR_FUSION", 7L, 3L, 0.60d));
    }

    private static StatsRow row(String strategy, Long success, Long failure, Double reward) {
        StatsRow row = mock(StatsRow.class);
        when(row.getStrategyName()).thenReturn(strategy);
        when(row.getSuccess()).thenReturn(success);
        when(row.getFailure()).thenReturn(failure);
        when(row.getReward()).thenReturn(reward);
        return row;
    }

    private static byte[] fixedSeed() {
        byte[] seed = new byte[32];
        for (int index = 0; index < seed.length; index++) {
            seed[index] = (byte) (index + 1);
        }
        return seed;
    }

    private record SelectionRun(
            StrategySelectorService.Strategy selected,
            SelectionDecisionLedger.Snapshot snapshot,
            List<EntropyCall> calls) {
    }

    private record EntropyCall(String operation, SelectionCoordinate coordinate, int bound) {
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
}
