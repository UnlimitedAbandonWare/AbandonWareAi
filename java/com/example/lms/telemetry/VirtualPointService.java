package com.example.lms.telemetry;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * VirtualPointService
 * - Converts snapshot map into a fixed-length feature vector and appends one NDJSON line.
 * - External dependency free (manual JSON writing).
 * - Thread-safe append, fail-soft (IO failure is swallowed).
 */
public class VirtualPointService {

    private final Object fileMutex = new Object();

    public List<Double> toVector(Map<String, Object> snapshot) {
        if (snapshot == null) snapshot = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) snapshot.getOrDefault("_order", Arrays.asList(
                "m1_source_mix_web","m1_source_mix_vector","m1_source_mix_kg",
                "m2_authority","m3_novelty","m4_contradiction","m5_rerank_cost",
                "m7_risk","m8_latency","m9_budget"
        ));
        List<Double> v = new ArrayList<>(order.size());
        for (String k : order) {
            Object val = snapshot.get(k);
            double d = 0.0;
            if (val instanceof Number) d = ((Number) val).doubleValue();
            else if (val != null) {
                try {
                    d = Double.parseDouble(String.valueOf(val));
                } catch (Exception ignore) {}
            }
            v.add(d);
        }
        return v;
    }

    public void appendNdjson(String requestId, Map<String, Object> snapshot, File file) {
        try {
            List<Double> vec = toVector(snapshot);
            String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date());
            StringBuilder sb = new StringBuilder(256);
            sb.append("{");
            sb.append("\"ts\":\"").append(escape(ts)).append("\",");
            sb.append("\"requestId\":\"").append(escape(nullToEmpty(requestId))).append("\",");
            sb.append("\"vector\":").append(toJsonArray(vec)).append(",");
            sb.append("\"snapshot\":").append(toShallowJson(snapshot));
            sb.append("}\n");
            String line = sb.toString();
            synchronized (fileMutex) {
                file.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(file, true);
                     OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    w.write(line);
                    w.flush();
                }
            }
        } catch (Exception ignore) {
            // Fail-soft: never break upstream
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String escape(String s) {
        return s.replace("\\","\\\\").replace("\"", "\\\"");
    }

    private static String toJsonArray(List<Double> v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<v.size();i++) {
            if (i>0) sb.append(',');
            double d = v.get(i);
            if (Double.isNaN(d) || Double.isInfinite(d)) d = 0.0;
            sb.append(String.format(java.util.Locale.US, "%.6f", d));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String toShallowJson(Map<String, Object> map) {
        if (map == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if ("_order".equals(e.getKey())) continue; // hide order in snapshot duplicate
            if (!first) sb.append(',');
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number) sb.append(String.valueOf(v));
            else if (v instanceof Boolean) sb.append(((Boolean) v) ? "true" : "false");
            else sb.append("\"").append(escape(String.valueOf(v))).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }
}