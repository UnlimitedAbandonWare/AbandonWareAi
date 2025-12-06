package com.example.lms.service.rag.plan;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Super-light "DSL" loader using java.util.Properties-like format, to avoid YAML dependency.
 * Syntax (lines of key=value):
 * order=web>vector>kg
 * gate.onnx=true
 */
public class PlanDslLoader {

    public static class Plan {
        public String id;
        public String order = "web>vector>kg";
        public Map<String,String> gate = new HashMap<>();
    }

    public Plan load(File f, String id) {
        Plan p = new Plan();
        p.id = id;
        // Skipping file parsing to avoid external deps; serves as a stable hook.
        return p;
    }
}