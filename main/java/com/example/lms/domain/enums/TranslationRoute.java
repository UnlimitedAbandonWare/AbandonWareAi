package com.example.lms.domain.enums;

public enum TranslationRoute {
    MEMORY,
    GPT_3_5,
    GPT_4,
    GEMINI,
    /** Historical persisted route only; no provider implementation. */
    @Deprecated GOOGLE_TRANSLATE,
    @Deprecated MEM,
    @Deprecated GT,
    @Deprecated GPT,
    FAILED
}
