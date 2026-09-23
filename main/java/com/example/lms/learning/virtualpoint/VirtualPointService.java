package com.example.lms.learning.virtualpoint;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class VirtualPointService {
    private static final int MAX = 256;
    private final LinkedHashMap<String, VirtualPoint> lru = new LinkedHashMap<>(16,0.75f,true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, VirtualPoint> eldest) {
            return size() > MAX;
        }
    };

    public synchronized void put(String key, VirtualPoint vp) {
        if (key != null && vp != null) lru.put(key, vp);
    }

    public synchronized Optional<VirtualPoint> get(String key) {
        return Optional.ofNullable(lru.get(key));
    }

    public synchronized Optional<Match> nearest(float[] vector, double minSimilarity) {
        if (vector == null || vector.length == 0 || lru.isEmpty()) {
            return Optional.empty();
        }
        double threshold = sanitizeThreshold(minSimilarity);
        Match best = null;
        for (Map.Entry<String, VirtualPoint> entry : lru.entrySet()) {
            VirtualPoint point = entry.getValue();
            if (point == null || point.vector.length != vector.length || point.vector.length == 0) {
                continue;
            }
            double similarity = cosine(vector, point.vector);
            if (similarity < threshold) {
                continue;
            }
            if (best == null || similarity > best.similarity()) {
                best = new Match(entry.getKey(), point, similarity);
            }
        }
        return Optional.ofNullable(best);
    }

    public synchronized int size() {
        return lru.size();
    }

    public synchronized List<Map<String, Object>> snapshot(int limit) {
        int safeLimit = Math.min(Math.max(1, limit), MAX);
        List<Map.Entry<String, VirtualPoint>> entries = new ArrayList<>(lru.entrySet());
        Collections.reverse(entries);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, VirtualPoint> entry : entries) {
            if (out.size() >= safeLimit) {
                break;
            }
            VirtualPoint point = entry.getValue();
            if (point == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", safeLabel(entry.getKey(), ""));
            row.put("patternId", point.patternId);
            row.put("dominantFailure", point.dominantFailure);
            row.put("restoreAction", point.restoreAction);
            row.put("riskScore", point.riskScore);
            row.put("priorityScore", point.priorityScore);
            row.put("seenAtMs", point.seenAtMs);
            row.put("dimension", point.vector == null ? 0 : point.vector.length);
            out.add(row);
        }
        return List.copyOf(out);
    }

    private static double sanitizeThreshold(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 1.0d;
        }
        if (value < -1.0d) {
            return -1.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            double av = finite(a[i]);
            double bv = finite(b[i]);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        if (normA <= 0.0d || normB <= 0.0d) {
            return 0.0d;
        }
        double out = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        if (Double.isNaN(out) || Double.isInfinite(out)) {
            return 0.0d;
        }
        return Math.max(-1.0d, Math.min(1.0d, out));
    }

    private static double finite(float value) {
        return Float.isFinite(value) ? value : 0.0d;
    }

    private static String safeLabel(String value, String fallback) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            s = fallback == null ? "" : fallback.trim().toLowerCase(Locale.ROOT);
        }
        s = s.replaceAll("[^a-z0-9_.:-]+", "_");
        if (s.length() > 96) {
            s = s.substring(0, 96);
        }
        return s.isBlank() ? "none" : s;
    }

    public record Match(String key, VirtualPoint point, double similarity) {
    }
}
