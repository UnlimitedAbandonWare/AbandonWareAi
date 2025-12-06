package com.example.lms.service.strategy;

import java.util.*;
import java.util.regex.*;



/**
 * MatrixPolicyEngine
 * - Infers a 3x3 "cell" from signals (freshness L/M/H x evidence Relax/Normal/Official)
 * - Produces an execution Plan (web/vector/kg weights, cross-encoder budget, domain profile, etc.)
 * - Allows override from env or external YAML (not strictly required to compile).
 *
 * NOTE: Minimal dependencies; fallback defaults are embedded. External YAML loader is optional.
 */
public class MatrixPolicyEngine {
    public enum Freshness { LOW, MID, HIGH }
    public enum Evidence { RELAX, NORMAL, OFFICIAL }

    public static final class Signals {
        public double freshnessScore; // 0..1
        public boolean officialCue;
        public String language; // e.g., "ko", "en"
        public String queryText;
    }

    public static final class Plan {
        public Freshness freshness;
        public Evidence evidence;
        public boolean useWeb;
        public boolean officialSourcesOnly;
        public String domainProfile; // e.g., "government_strict"
        public int webTopK;
        public double rrfWeb;
        public double rrfVector;
        public double rrfKg;
        public boolean kgEnabled;
        public boolean hedgeWeb;
        public int webTimeoutMs;
        public boolean crossEncoderEnabled;
        public int crossEncoderTopK;
        public double crossEncoderCutoff;
        public String cellKey() { return freshness.name().toLowerCase() + "+" + evidence.name().toLowerCase(); }
    }

    public static Plan planFromSignals(Signals sig) {
        Freshness f = inferFreshness(sig);
        Evidence e = inferEvidence(sig);
        return planForCell(f, e);
    }

    public static Freshness inferFreshness(Signals s) {
        // naive heuristic: keywords + score
        String q = Optional.ofNullable(s.queryText).orElse("").toLowerCase(Locale.ROOT);
        if (q.contains("today") || q.contains("오늘") || q.contains("latest") || q.contains("최신") || s.freshnessScore >= 0.66) return Freshness.HIGH;
        if (q.contains("recent") || q.contains("최근") || s.freshnessScore >= 0.33) return Freshness.MID;
        return Freshness.LOW;
    }

    public static Evidence inferEvidence(Signals s) {
        String q = Optional.ofNullable(s.queryText).orElse("").toLowerCase(Locale.ROOT);
        if (q.contains("official") || q.contains("정부") || q.contains("공식") || s.officialCue) return Evidence.OFFICIAL;
        if (q.contains("근거") || q.contains("출처") || q.contains("논문")) return Evidence.NORMAL;
        return Evidence.RELAX;
    }

    public static Plan planForCell(Freshness f, Evidence e) {
        // Default hardcoded policy map (can be replaced by YAML in production).
        Plan p = new Plan();
        p.freshness = f; p.evidence = e;

        String key = f.name().toLowerCase() + "+" + e.name().toLowerCase();
        switch (key) {
            case "high+official":
                p.useWeb = true;
                p.officialSourcesOnly = true;
                p.domainProfile = "government_strict";
                p.webTopK = 12;
                p.rrfWeb = 1.0; p.rrfVector = 0.3; p.rrfKg = 0.0;
                p.kgEnabled = false;
                p.hedgeWeb = true;
                p.webTimeoutMs = 2500;
                p.crossEncoderEnabled = true;
                p.crossEncoderTopK = 30;
                p.crossEncoderCutoff = 0.55;
                break;
            case "high+normal":
                p.useWeb = true;
                p.officialSourcesOnly = false;
                p.domainProfile = "news_balanced";
                p.webTopK = 10;
                p.rrfWeb = 0.8; p.rrfVector = 0.6; p.rrfKg = 0.2;
                p.kgEnabled = true;
                p.hedgeWeb = true;
                p.webTimeoutMs = 2500;
                p.crossEncoderEnabled = true;
                p.crossEncoderTopK = 20;
                p.crossEncoderCutoff = 0.50;
                break;
            case "low+relax":
            default:
                p.useWeb = false;
                p.officialSourcesOnly = false;
                p.domainProfile = "default";
                p.webTopK = 0;
                p.rrfWeb = 0.0; p.rrfVector = 1.0; p.rrfKg = 0.3;
                p.kgEnabled = true;
                p.hedgeWeb = false;
                p.webTimeoutMs = 2000;
                p.crossEncoderEnabled = false;
                p.crossEncoderTopK = 0;
                p.crossEncoderCutoff = 0.0;
                break;
        }
        return p;
    }
}