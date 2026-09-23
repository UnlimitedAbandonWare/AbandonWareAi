package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a compact, redacted CFVM raw-tile manifest from the process-local
 * matrix buffer without storing raw trace payloads.
 */
@Component
public class CfvmRawTileBuilder {
    public static final String DISABLED_REASON = "no_raw_matrix_entries";

    public Map<String, Object> build(String patternHex, RawMatrixBuffer buffer) {
        String patternId = SafeRedactor.traceLabelOrFallback(patternHex, "unknown");
        List<RawMatrixBuffer.Entry> entries = buffer == null ? List.of() : buffer.snapshot();
        int bufferSize = entries.size();
        if (entries.isEmpty()) {
            traceDisabled(patternId, bufferSize);
            return Map.of(
                    "kind", "cfvm_raw_tile",
                    "patternId", patternId,
                    "enabled", false,
                    "disabledReason", DISABLED_REASON,
                    "bufferSize", bufferSize);
        }

        double[] weights = buffer.getWeights();
        List<Map<String, Object>> slots = compactSlots(entries, weights);
        int dominantSlot = dominantSlot(weights, slots.size());
        TraceStore.put("cfvm.rawTile.enabled", true);
        TraceStore.put("cfvm.rawTile.disabledReason", null);
        TraceStore.put("cfvm.rawTile.patternId", patternId);
        TraceStore.put("cfvm.rawTile.bufferSize", bufferSize);
        TraceStore.put("cfvm.rawTile.slotCount", slots.size());
        TraceStore.put("cfvm.rawTile.dominantSlot", dominantSlot);
        TraceStore.put("cfvm.rawTile.condensed", true);
        TraceStore.put("cfvm.rawTile.rawPayloadStored", false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "cfvm_raw_tile");
        out.put("patternId", patternId);
        out.put("enabled", true);
        out.put("bufferSize", bufferSize);
        out.put("slotCount", slots.size());
        out.put("dominantSlot", dominantSlot);
        out.put("slots", slots);
        out.put("rawPayloadStored", false);
        return out;
    }

    private static void traceDisabled(String patternId, int bufferSize) {
        TraceStore.put("cfvm.rawTile.enabled", false);
        TraceStore.put("cfvm.rawTile.disabledReason", DISABLED_REASON);
        TraceStore.put("cfvm.rawTile.patternId", patternId);
        TraceStore.put("cfvm.rawTile.bufferSize", bufferSize);
        TraceStore.put("cfvm.rawTile.slotCount", 0);
        TraceStore.put("cfvm.rawTile.condensed", false);
        TraceStore.put("cfvm.rawTile.rawPayloadStored", false);
    }

    private static List<Map<String, Object>> compactSlots(List<RawMatrixBuffer.Entry> entries, double[] weights) {
        List<Map<String, Object>> slots = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            RawMatrixBuffer.Entry entry = entries.get(i);
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("slot", i);
            slot.put("patternId", Long.toHexString(entry.patternId()));
            slot.put("traceSize", Math.max(0L, entry.traceSize()));
            slot.put("signatureLength", Math.max(0L, entry.signatureLength()));
            slot.put("weight", weightAt(weights, i));
            slots.add(slot);
        }
        return List.copyOf(slots);
    }

    private static int dominantSlot(double[] weights, int slotCount) {
        if (weights == null || weights.length == 0 || slotCount <= 0) {
            return -1;
        }
        int end = Math.min(weights.length, slotCount);
        int best = 0;
        double bestWeight = weightAt(weights, 0);
        for (int i = 1; i < end; i++) {
            double candidate = weightAt(weights, i);
            if (candidate > bestWeight) {
                best = i;
                bestWeight = candidate;
            }
        }
        return best;
    }

    private static double weightAt(double[] weights, int index) {
        if (weights == null || index < 0 || index >= weights.length) {
            return 0.0d;
        }
        double weight = weights[index];
        return Double.isFinite(weight) ? weight : 0.0d;
    }
}
