package com.example.lms.infra.selection;

import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.Mac;

public final class SelectionEntropyFactory {

    private SelectionEntropyFactory() {
    }

    public static SelectionEntropy standard() {
        return LiveSelectionEntropy.INSTANCE;
    }

    public static SelectionEntropy replay(SelectionReplaySpec spec) {
        return replay(spec, () -> Mac.getInstance("HmacSHA256"));
    }

    @FunctionalInterface
    interface MacProbe {
        Mac create() throws GeneralSecurityException;
    }

    static SelectionEntropy replay(SelectionReplaySpec spec, MacProbe probe) {
        if (spec == null
                || !ReplaySelectionEntropy.ALGORITHM_VERSION.equals(spec.algorithmVersion())) {
            throw new SelectionEntropyException(SelectionEntropyReason.ALGORITHM_UNSUPPORTED);
        }
        try {
            Objects.requireNonNull(probe, "probe").create();
            return new ReplaySelectionEntropy(spec.copySeedForConstruction());
        } catch (GeneralSecurityException failure) {
            throw new SelectionEntropyException(SelectionEntropyReason.REPLAY_INIT_FAILED);
        }
    }
}
