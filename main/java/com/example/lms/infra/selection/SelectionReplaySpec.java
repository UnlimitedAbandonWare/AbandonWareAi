package com.example.lms.infra.selection;

import java.util.Arrays;

public final class SelectionReplaySpec {

    private final String algorithmVersion;
    private final byte[] seed;
    private final String seedFingerprint;

    private SelectionReplaySpec(String algorithmVersion, byte[] seed) {
        if (seed == null || seed.length < 16 || seed.length > 64) {
            throw new SelectionEntropyException(SelectionEntropyReason.REPLAY_INVALID);
        }
        this.algorithmVersion = algorithmVersion;
        this.seed = Arrays.copyOf(seed, seed.length);
        this.seedFingerprint = ReplaySelectionEntropy.fingerprint(this.seed);
    }

    public static SelectionReplaySpec v1(byte[] seed) {
        return new SelectionReplaySpec(ReplaySelectionEntropy.ALGORITHM_VERSION, seed);
    }

    public String algorithmVersion() {
        return algorithmVersion;
    }

    public String seedFingerprint() {
        return seedFingerprint;
    }

    byte[] copySeedForConstruction() {
        return Arrays.copyOf(seed, seed.length);
    }

    @Override
    public String toString() {
        return "SelectionReplaySpec[algorithmVersion=" + algorithmVersion
                + ", seedFingerprint=" + seedFingerprint + "]";
    }
}
