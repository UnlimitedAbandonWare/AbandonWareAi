package com.example.lms.learning.chat;

public enum LearningSignalKind {
    ATTENDANCE,
    ASSIGNMENT,
    SUBMISSION,
    GRADE,
    FEEDBACK,
    AUTOLEARN,
    RAG_OPS,
    CONTEXT_DEGRADED;

    public String value() {
        return switch (this) {
            case ATTENDANCE -> "TRAINING_ACTIVITY";
            case ASSIGNMENT -> "TRAINING_TASK";
            case SUBMISSION -> "TRAINING_SAMPLE";
            case GRADE -> "QUALITY_SIGNAL";
            case FEEDBACK, AUTOLEARN, RAG_OPS, CONTEXT_DEGRADED -> name();
        };
    }
}
