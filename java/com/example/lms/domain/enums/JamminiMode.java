package com.example.lms.domain.enums;

/**
 * High level operating mode for the Jammini orchestrator.
 *
 * <ul>
 *   <li>{@code JAMMINI_PROJECT}: default safe / memory‑centric mode.</li>
 *   <li>{@code FREEPLAY}: experimental mode without persistent memory.</li>
 *   <li>{@code HYPERNOVA_DEBUG}: heavy retrieval + diagnostics.</li>
 *   <li>{@code ZERO_BREAK}: final overdrive mode, usually admin‑only.</li>
 * </ul>
 *
 * The enum is intentionally small; more specialised tuning should happen
 * through GuardProfile / MemoryProfile / plan DSL rather than additional
 * constants here.
 */
public enum JamminiMode {
    JAMMINI_PROJECT,
    FREEPLAY,
    HYPERNOVA_DEBUG,
    ZERO_BREAK;

    /**
     * Returns true when the mode is allowed to behave more aggressively
     * in terms of exploration / retrieval fan‑out.
     */
    public boolean isAggressive() {
        return this == FREEPLAY || this == HYPERNOVA_DEBUG || this == ZERO_BREAK;
    }

    /**
     * Whether it is generally safe to write long‑term memory in this mode.
     */
    public boolean isMemoryWriteSafe() {
        return this == JAMMINI_PROJECT;
    }
}
