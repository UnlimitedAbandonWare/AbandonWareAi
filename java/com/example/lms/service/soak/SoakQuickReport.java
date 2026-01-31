package com.example.lms.service.soak;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight, fixed-schema JSON report used for quick soak checks.
 *
 * <p>
 * The goal is to provide a stable response shape (schemaVersion) with a few
 * key metrics (successRate/evidenceRate) without the heavier SoakReport format.
 * </p>
 */
public class SoakQuickReport {

    public String schemaVersion = "soak-quick-v1";

    public String topic;
    public int k;

    public Instant startedAt;
    public Instant finishedAt;

    public Metrics metrics = new Metrics();
    public List<Item> items = new ArrayList<>();

    public static class Metrics {
        public int total;
        public int success;
        public int evidence;

        public double successRate;
        public double evidenceRate;

        /** (compat) retrieval produced at least one doc (roughly == successRate in current implementation) */
        public double ragHitRate;

        /** Best-effort timeout rate hint (used in ops dashboards). */
        public double naverSoftTimedOutRate;

        public long avgLatencyMs;
        public long p95LatencyMs;
    }

    public static class Item {
        public String query;
        public boolean success;
        public boolean evidence;
        public long latencyMs;

        /** Optional note for debugging (kept short; do not include secrets). */
        public String note;

        // Optional top evidence fields (used for Soak→Dataset accumulation).
        public String topSnippet;
        public String topUrl;
        public String topSource;

        public boolean isSuccess() {
            return success;
        }

        public boolean isHasEvidence() {
            return evidence;
        }
    }
}
