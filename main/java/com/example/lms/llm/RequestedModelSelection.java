package com.example.lms.llm;

import com.example.lms.search.TraceStore;

/** The explicitly selected identity is internal request state, never public trace data. */
public final class RequestedModelSelection {
    private static final String KEY = "chat.internal.exactModelSelection";
    private RequestedModelSelection() {}
    public static void begin(String model) {
        TraceStore.putInternal(KEY, model == null || model.isBlank() ? null : model.trim());
    }
    public static boolean matches(String model) {
        Object requested = TraceStore.get(KEY);
        return requested instanceof String id && id.equals(model);
    }
}
