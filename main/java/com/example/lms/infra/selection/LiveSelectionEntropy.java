package com.example.lms.infra.selection;

import java.util.concurrent.ThreadLocalRandom;

final class LiveSelectionEntropy implements SelectionEntropy {

    static final LiveSelectionEntropy INSTANCE = new LiveSelectionEntropy();

    private LiveSelectionEntropy() {
    }

    @Override
    public SelectionEntropyMode mode() {
        return SelectionEntropyMode.STANDARD;
    }

    @Override
    public String algorithmVersion() {
        return ReplaySelectionEntropy.ALGORITHM_VERSION;
    }

    @Override
    public double unitInterval(SelectionCoordinate coordinate) {
        requireCoordinate(coordinate);
        return ThreadLocalRandom.current().nextDouble();
    }

    @Override
    public int boundedIndex(SelectionCoordinate coordinate, int bound) {
        requireCoordinate(coordinate);
        if (bound < 1 || bound > 1_000_000) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }
        return ThreadLocalRandom.current().nextInt(bound);
    }

    private static void requireCoordinate(SelectionCoordinate coordinate) {
        if (coordinate == null) {
            throw new SelectionEntropyException(SelectionEntropyReason.COORDINATE_INVALID);
        }
    }
}
