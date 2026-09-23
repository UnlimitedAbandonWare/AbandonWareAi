package com.abandonwareai.zerobreak.strategy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Minimal Plan loader that reads YAML text from the ops/zerobreak/plans folder at runtime. */
public class PlannerNexus {
    private final String baseDir;
    public PlannerNexus(String baseDir) { this.baseDir = baseDir; }

    public String loadPlanYaml(String planId) throws IOException {
        String file = switch (planId) {
            case "zero_break.v1" -> "zero_break.v1.yaml";
            case "brave.v1" -> "brave.v1.yaml";
            case "safe_autorun.v1" -> "safe_autorun.v1.yaml";
            default -> "safe_autorun.v1.yaml";
        };
        Path p = Path.of(baseDir, file);
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}