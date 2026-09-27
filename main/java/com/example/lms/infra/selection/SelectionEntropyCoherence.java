package com.example.lms.infra.selection;

public enum SelectionEntropyCoherence {
    NOT_REQUESTED("not_requested"),
    ACCEPTED("accepted"),
    MATCHED("matched"),
    PARTIAL("partial"),
    FAILED("failed"),
    FORBIDDEN("forbidden"),
    INVALID("invalid");

    private final String wireValue;

    SelectionEntropyCoherence(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
