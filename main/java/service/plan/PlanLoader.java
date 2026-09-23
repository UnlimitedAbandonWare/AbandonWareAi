package service.plan;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal plan loader that reads YAML-like key-value pairs.
 */
public class PlanLoader {
    private static final Logger log = LoggerFactory.getLogger(PlanLoader.class);

    public static class Plan {
        public String name = "default";
        public double webWeight = 0.7;
        public double ragWeight = 0.3;
        public int webTopK = 10;
        public int timeoutMs = 1800;
        public boolean calibratedRrf = true;
        public int onnxMaxConcurrency = 4;
        public int onnxBudgetMs = 1800;
        public int citationMinSources = 2;
        public int citationMinChars = 400;
    }

    public Plan load(Path yamlPath) {
        Plan p = new Plan();
        if (yamlPath == null || !Files.exists(yamlPath)) return p;
        try {
            List<String> lines = Files.readAllLines(yamlPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                String t = line.trim().toLowerCase();
                if (t.startsWith("name:")) p.name = line.split(":",2)[1].trim();
                if (t.contains("web-weight")) p.webWeight = parseDouble(line);
                if (t.contains("rag-weight")) p.ragWeight = parseDouble(line);
                if (t.contains("webtopk")) p.webTopK = (int)parseDouble(line);
                if (t.contains("timeoutms")) p.timeoutMs = (int)parseDouble(line);
                if (t.contains("calibratedrrf")) p.calibratedRrf = line.contains("true");
                if (t.contains("maxconcurrency")) p.onnxMaxConcurrency = (int)parseDouble(line);
                if (t.contains("budgetms")) p.onnxBudgetMs = (int)parseDouble(line);
                if (t.contains("minofficialsources")) p.citationMinSources = (int)parseDouble(line);
                if (t.contains("minsnippetchars")) p.citationMinChars = (int)parseDouble(line);
            }
        } catch (Exception error) {
            logFailSoft("load", error);
        }
        return p;
    }

    private double parseDouble(String line) {
        try {
            String[] toks = line.split(":");
            return Double.parseDouble(toks[toks.length-1].trim().replaceAll("[^0-9.]", ""));
        } catch (Exception error) {
            logFailSoft("parseDouble", error);
            return 0.0;
        }
    }

    private static void logFailSoft(String stage, Exception error) {
        if (log.isDebugEnabled()) {
            log.debug("[PlanLoader] fail-soft stage={} errorType={}", stage, errorType(error));
        }
    }

    private static String errorType(Exception error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
