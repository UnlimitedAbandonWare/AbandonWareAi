package com.example.lms.cfvm;

/**
 * Compatibility shim for older toy matchers that only need a stage enum.
 * This is not the production CFVM RawTile or failure-matrix data model.
 */
public final class RawSlot {
    private RawSlot(){}
    public enum Stage { START, MIDDLE, END }
}
