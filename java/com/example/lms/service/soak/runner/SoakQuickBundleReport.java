package com.example.lms.service.soak.runner;

import com.example.lms.service.soak.SoakQuickReport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * quick_report.json (bundle) schema.
 *
 * <p>Purpose:
 * <ul>
 *   <li>Run a fixed 10-query soak set per provider (NAVER/BRAVE)
 *   <li>Bundle provider runs into a single JSON for dashboards/CI
 *   <li>Include a simple gate (PASS/WARN/FAIL) based on hit/evidence metrics
 * </ul>
 */
public class SoakQuickBundleReport {

    public String schema = "soak.quick_report.v3";
    public Instant generatedAt = Instant.now();

    public String topic;
    public int k;

    public GateConfig gate = new GateConfig();
    public List<ProviderRun> providers = new ArrayList<>();
    public Summary summary = new Summary();

    public static class GateConfig {
        public double warnEvidenceMin;
        public double failEvidenceMin;
        public double warnHitMin;
        public double failHitMin;
        public int warnExitCode;
        public int failExitCode;
    }

    public static class ProviderRun {
        public String provider;
        public GateDecision gate;
        public SoakQuickReport report;
        public ProviderMetrics metrics;
    }

    public static class ProviderMetrics {
        public long fpFilterLegacyBypassCount;
        public long webCalls;
        public long webCallsWithNaver;
        public long webMergedTotal;
        public long webMergedFromNaver;
        public double naverCallInclusionRate;
        public double naverMergedShare;
    }

    public static class GateDecision {
        public String status; // PASS/WARN/FAIL
        public double hitRate;
        public double evidenceRate;
        public List<String> reasons = new ArrayList<>();
    }

    public static class Summary {
        public String overallStatus; // PASS/WARN/FAIL
        public List<String> failedProviders = new ArrayList<>();
        public List<String> warnedProviders = new ArrayList<>();
        public int exitCode;

        // metrics (aggregated across providers)
        public long totalFpFilterLegacyBypassCount;
        public double overallNaverCallInclusionRate;
        public double overallNaverMergedShare;
    }
}
