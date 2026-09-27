package com.example.lms.infra.exec;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.infra.selection.SelectionReplaySpec;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextPropagationTimeBudgetTest {

    @AfterEach
    void clearBudget() {
        TimeBudgetContext.clear();
        GuardContextHolder.clear();
    }

    @Test
    void runnableCapturesBudgetAndRestoresWorkerBudget() {
        TimeBudget captured = new TimeBudget(1_000);
        TimeBudget workerPrevious = new TimeBudget(5_000);
        AtomicReference<TimeBudget> seen = new AtomicReference<>();
        TimeBudgetContext.set(captured);
        Runnable wrapped = ContextPropagation.wrap(() -> seen.set(TimeBudgetContext.get()));
        TimeBudgetContext.set(workerPrevious);

        wrapped.run();

        assertSame(captured, seen.get());
        assertSame(workerPrevious, TimeBudgetContext.get());
    }

    @Test
    void supplierClearsMissingCapturedBudgetAndRestoresWorkerBudget() {
        TimeBudgetContext.clear();
        var wrapped = ContextPropagation.wrapSupplier(TimeBudgetContext::get);
        TimeBudget workerPrevious = new TimeBudget(5_000);
        TimeBudgetContext.set(workerPrevious);

        TimeBudget seen = wrapped.get();

        assertNull(seen);
        assertSame(workerPrevious, TimeBudgetContext.get());
    }

    @Test
    void callableCapturesBudgetAndCleansWorkerWhenPreviouslyEmpty() throws Exception {
        TimeBudget captured = new TimeBudget(1_000);
        TimeBudgetContext.set(captured);
        Callable<TimeBudget> wrapped = ContextPropagation.wrapCallable(TimeBudgetContext::get);
        TimeBudgetContext.clear();

        TimeBudget seen = wrapped.call();

        assertSame(captured, seen);
        assertNull(TimeBudgetContext.get());
    }

    @Test
    void runnableCarriesSelectionReferencesAndRestoresWorkerContext() {
        SelectionCarrier captured = replayCarrier();
        GuardContextHolder.set(captured.context());
        AtomicReference<SelectionEntropy> seenEntropy = new AtomicReference<>();
        AtomicReference<SelectionDecisionLedger> seenLedger = new AtomicReference<>();
        Runnable wrapped = ContextPropagation.wrap(() -> {
            seenEntropy.set(GuardContextHolder.get().selectionEntropy());
            seenLedger.set(GuardContextHolder.get().selectionDecisionLedger());
        });
        GuardContext workerPrior = GuardContext.defaultContext();
        GuardContextHolder.set(workerPrior);

        wrapped.run();

        assertSame(captured.entropy(), seenEntropy.get());
        assertSame(captured.ledger(), seenLedger.get());
        assertSame(workerPrior, GuardContextHolder.get());
    }

    @Test
    void supplierCarriesSelectionReferencesAndRestoresWorkerContext() {
        SelectionCarrier captured = replayCarrier();
        GuardContextHolder.set(captured.context());
        var wrapped = ContextPropagation.wrapSupplier(() ->
                GuardContextHolder.get().selectionEntropy() == captured.entropy()
                        && GuardContextHolder.get().selectionDecisionLedger() == captured.ledger());
        GuardContext workerPrior = GuardContext.defaultContext();
        GuardContextHolder.set(workerPrior);

        assertTrue(wrapped.get());
        assertSame(workerPrior, GuardContextHolder.get());
    }

    @Test
    void callableCarriesSelectionReferencesAndRestoresWorkerContext() throws Exception {
        SelectionCarrier captured = replayCarrier();
        GuardContextHolder.set(captured.context());
        Callable<Boolean> wrapped = ContextPropagation.wrapCallable(() ->
                GuardContextHolder.get().selectionEntropy() == captured.entropy()
                        && GuardContextHolder.get().selectionDecisionLedger() == captured.ledger());
        GuardContext workerPrior = GuardContext.defaultContext();
        GuardContextHolder.set(workerPrior);

        assertTrue(wrapped.call());
        assertSame(workerPrior, GuardContextHolder.get());
    }

    @Test
    void nestedWrapperRestoresTheOuterAndThenWorkerContext() {
        SelectionCarrier captured = replayCarrier();
        GuardContextHolder.set(captured.context());
        Runnable outer = ContextPropagation.wrap(() -> {
            assertSame(captured.context(), GuardContextHolder.get());
            Runnable inner = ContextPropagation.wrap(() -> {
                assertSame(captured.entropy(), GuardContextHolder.get().selectionEntropy());
                assertSame(captured.ledger(), GuardContextHolder.get().selectionDecisionLedger());
            });
            inner.run();
            assertSame(captured.context(), GuardContextHolder.get());
        });
        GuardContext workerPrior = GuardContext.defaultContext();
        GuardContextHolder.set(workerPrior);

        outer.run();

        assertSame(workerPrior, GuardContextHolder.get());
    }

    @Test
    void exceptionCleanupRestoresTheWorkerContext() {
        SelectionCarrier captured = replayCarrier();
        GuardContextHolder.set(captured.context());
        Callable<Void> failing = ContextPropagation.wrapCallable(() -> {
            assertSame(captured.entropy(), GuardContextHolder.get().selectionEntropy());
            assertSame(captured.ledger(), GuardContextHolder.get().selectionDecisionLedger());
            throw new IllegalStateException("fixed-test-failure");
        });
        GuardContext workerPrior = GuardContext.defaultContext();
        GuardContextHolder.set(workerPrior);

        assertThatThrownBy(failing::call).hasMessage("fixed-test-failure");
        assertSame(workerPrior, GuardContextHolder.get());
    }

    private static SelectionCarrier replayCarrier() {
        GuardContext context = GuardContext.defaultContext();
        SelectionEntropy entropy =
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        context.attachSelectionEntropy(entropy, ledger);
        return new SelectionCarrier(context, entropy, ledger);
    }

    private record SelectionCarrier(
            GuardContext context,
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger) {
    }
}
