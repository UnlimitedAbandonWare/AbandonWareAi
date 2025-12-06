package com.example.guard;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

public class FinalQualityGateTest {
    FinalQualityGate gate = new FinalQualityGate();

    private FinalQualityGate.GateProps props() {
        return new FinalQualityGate.GateProps(0.35, 0.35, 0.20, 0.10, 9.0, 0.55, 0.90, 2);
    }

    @Test
    void pass_whenEvidenceStrong_AuthorityHigh_ContradictionLow() {
        var s = new FinalQualityGate.Signals(4, 0.9, 0.05, 0.8);
        assertTrue(gate.pass(s, props()));
    }

    @Test
    void fail_whenContradictionHigh() {
        var s = new FinalQualityGate.Signals(4, 0.9, 0.7, 0.8);
        assertFalse(gate.pass(s, props()));
    }

    @Test
    void fail_whenMinEvidenceNotMet() {
        var s = new FinalQualityGate.Signals(1, 0.95, 0.05, 0.9);
        assertFalse(gate.pass(s, props()));
    }
}