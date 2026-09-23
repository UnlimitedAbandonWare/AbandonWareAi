package com.example.lms.service.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.infra.selection.SelectionEntropyMode;
import com.example.lms.infra.selection.SelectionReplaySpec;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class GuardContextSelectionEntropyTest {

    @Test
    void attachmentPredicateIgnoresLazyDefaultsAndBecomesTrueForTheExplicitPair() {
        GuardContext defaults = new GuardContext();
        assertThat(defaults.hasAttachedSelectionEntropy()).isFalse();

        defaults.selectionEntropy();
        defaults.selectionDecisionLedger();
        assertThat(defaults.hasAttachedSelectionEntropy()).isFalse();

        GuardContext attached = new GuardContext();
        SelectionEntropy replay =
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        attached.attachSelectionEntropy(replay, ledger);

        assertThat(attached.hasAttachedSelectionEntropy()).isTrue();
    }

    @Test
    void defaultsToStandardAndAttachesReplayOnlyOnce() {
        GuardContext context = GuardContext.defaultContext();
        assertThat(context.selectionEntropy().mode()).isEqualTo(SelectionEntropyMode.STANDARD);

        SelectionEntropy replay =
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        context.attachSelectionEntropy(replay, ledger);
        context.attachSelectionEntropy(replay, ledger);

        assertThat(context.selectionEntropy()).isSameAs(replay);
        assertThat(context.selectionDecisionLedger()).isSameAs(ledger);
        assertThat(context.copy().selectionEntropy()).isSameAs(replay);
        assertThat(context.copy().selectionDecisionLedger()).isSameAs(ledger);
    }

    @Test
    void rejectsReplacementWithAnotherCarrierOrLedger() {
        GuardContext context = GuardContext.defaultContext();
        SelectionEntropy first =
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        context.attachSelectionEntropy(first, ledger);

        byte[] anotherSeed = new byte[32];
        anotherSeed[0] = 1;
        SelectionEntropy second =
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(anotherSeed));
        assertThatThrownBy(() -> context.attachSelectionEntropy(second, ledger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("selection_entropy_context_already_attached");
        assertThatThrownBy(() -> context.attachSelectionEntropy(
                        first, SelectionDecisionLedger.forReplay()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("selection_entropy_context_already_attached");
    }

    @Test
    void copySharesTheAttachMonitorForAnAtomicSelectionPair() throws Exception {
        int attachModifiers = GuardContext.class.getMethod(
                "attachSelectionEntropy",
                SelectionEntropy.class,
                SelectionDecisionLedger.class).getModifiers();
        int copyModifiers = GuardContext.class.getMethod("copy").getModifiers();

        assertThat(Modifier.isSynchronized(attachModifiers)).isTrue();
        assertThat(Modifier.isSynchronized(copyModifiers)).isTrue();
    }
}
