package com.example.lms.infra.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LiveSelectionEntropyTest {

    private static final SelectionCoordinate COORDINATE =
            new SelectionCoordinate("router.pick", "route:auto", 0L, 0L);

    @Test
    void returnsFiniteValuesInsideTheRequiredRanges() {
        SelectionEntropy entropy = SelectionEntropyFactory.standard();

        assertThat(entropy.mode()).isEqualTo(SelectionEntropyMode.STANDARD);
        assertThat(entropy.algorithmVersion()).isEqualTo("selection-entropy-v1");
        for (int draw = 0; draw < 2_000; draw++) {
            assertThat(entropy.unitInterval(COORDINATE)).isBetween(0.0d, Math.nextDown(1.0d));
            assertThat(entropy.boundedIndex(COORDINATE, 97)).isBetween(0, 96);
        }
    }

    @Test
    void rejectsInvalidCoordinateAndBoundsWithFixedReasons() {
        SelectionEntropy entropy = SelectionEntropyFactory.standard();

        assertReason(() -> entropy.unitInterval(null), SelectionEntropyReason.COORDINATE_INVALID);
        assertReason(() -> entropy.boundedIndex(null, 10), SelectionEntropyReason.COORDINATE_INVALID);
        assertReason(() -> entropy.boundedIndex(COORDINATE, 0), SelectionEntropyReason.DERIVATION_INVALID);
        assertReason(
                () -> entropy.boundedIndex(COORDINATE, 1_000_001),
                SelectionEntropyReason.DERIVATION_INVALID);
    }

    private static void assertReason(Runnable operation, SelectionEntropyReason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(reason);
    }
}
