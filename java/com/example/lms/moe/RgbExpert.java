package com.example.lms.moe;

/**
 * RGB expert identifiers.
 *
 * <p>Policy: BLUE(Gemini) is used only from the offline/idle training pipeline.
 */
public enum RgbExpert {
    RED_3090,
    GREEN_3060,
    BLUE_GEMINI;

    public boolean isLocal() {
        return this == RED_3090 || this == GREEN_3060;
    }

    public boolean isRemote() {
        return this == BLUE_GEMINI;
    }

    /** Short id for logs/metrics. */
    public String id() {
        return switch (this) {
            case RED_3090 -> "R";
            case GREEN_3060 -> "G";
            case BLUE_GEMINI -> "B";
        };
    }
}
