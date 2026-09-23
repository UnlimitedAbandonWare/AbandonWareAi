package com.example.lms.learning.chat;

public enum LearningSourceTag {
    SUBMISSION,
    FEEDBACK,
    RAG_OPS;

    public String value() {
        return name();
    }
}
