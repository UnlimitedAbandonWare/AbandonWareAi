package com.example.lms.infra.selection;

public enum SelectionEntropyMode {
    STANDARD("standard"),
    REPLAY("replay");

    private final String wireValue;

    SelectionEntropyMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
