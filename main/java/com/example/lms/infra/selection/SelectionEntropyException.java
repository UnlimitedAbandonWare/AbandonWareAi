package com.example.lms.infra.selection;

import java.util.Objects;

public final class SelectionEntropyException extends RuntimeException {

    private final SelectionEntropyReason reason;

    public SelectionEntropyException(SelectionEntropyReason reason) {
        super(Objects.requireNonNull(reason, "reason").code());
        this.reason = reason;
    }

    public SelectionEntropyReason reason() {
        return reason;
    }
}
