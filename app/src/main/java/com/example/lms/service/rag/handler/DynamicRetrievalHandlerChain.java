package com.example.lms.service.rag.handler;

import java.util.*;

/**
 * Minimal, compile-safe dynamic ordering chain.
 */
public class DynamicRetrievalHandlerChain {

    private final List<String> stages = new ArrayList<>();

    public DynamicRetrievalHandlerChain() {
        // Default ordering: Vector -> Web -> KG
        stages.add("VECTOR");
        stages.add("WEB");
        stages.add("EXPERT_OPEN");
        stages.add("DPP");
        stages.add("ONNX");
        stages.add("KG");
    }

    /** Returns an immutable view of stage ordering. */
    public List<String> order() {
        return Collections.unmodifiableList(stages);
    }
}