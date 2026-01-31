package com.abandonware.ai.addons.synthesis;

import org.springframework.stereotype.Component;

import com.abandonware.ai.addons.config.AddonsProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;




@Component("synthesisMatrixTransformer")
public class MatrixTransformer {

    private final AddonsProperties props;
    private final MoEGate moe;

    public MatrixTransformer(AddonsProperties props) {
        this.props = props;
        this.moe = new MoEGate(props.getSynthesis().getMoeMix());
    }

    public String synthesize(List<ContextItem> items, int maxBytes) {
        if (items == null || items.isEmpty()) return "";
        Map<String, Double> authorityTier = props.getSynthesis().getAuthorityTier();
        List<ContextItem> dedup = reduceRedundancy(items);
        Map<String, Integer> allocation = allocateLines(dedup, authorityTier, maxBytes);
        StringBuilder sb = new StringBuilder();
        dedup.stream()
                        .sorted(Comparator.comparingInt((ContextItem ci) -> allocation.getOrDefault(ci.id(), 0)).reversed())
                .filter(ci -> allocation.getOrDefault(ci.id(), 0) > 0)
                .forEach(ci -> {
                    sb.append("### ").append(ci.title()).append(" (").append(ci.source()).append(")\n");
                    String s = ci.snippet();
                    int keep = allocation.getOrDefault(ci.id(), 0);
                    if (s.getBytes(StandardCharsets.UTF_8).length > keep) {
                        s = truncateBytes(s, keep);
                    }
                    sb.append(s).append("\n\n");
                });
        return sb.toString();
    }

    private static String truncateBytes(String s, int maxBytes) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return s;
        return new String(Arrays.copyOf(b, maxBytes), StandardCharsets.UTF_8) + "/* ... *&#47;";
    }

    private List<ContextItem> reduceRedundancy(List<ContextItem> items) {
        List<ContextItem> out = new ArrayList<>();
        Set<Long> sigs = new HashSet<>();
        for (ContextItem ci : items) {
            long sig = simHash(ci.snippet());
            boolean nearDup = sigs.stream().anyMatch(s -> hamming(s, sig) <= 6);
            if (!nearDup) {
                out.add(ci);
                sigs.add(sig);
            }
        }
        return out;
    }

    private Map<String, Integer> allocateLines(List<ContextItem> items,
                                               Map<String, Double> authTier,
                                               int maxBytes) {
        Map<String, Double> dynScore = new HashMap<>();
        for (ContextItem ci : items) {
            double a = authTier.getOrDefault(ci.source(), 0.5);
            double novelty = 1.0;
            double d = 0.6*a + 0.4*novelty;
            double h = 0.5*(1.0/(ci.rank()+1)) + 0.5*ci.score();
            double mix = moe.mix(h, d);
            dynScore.put(ci.id(), mix);
        }
        double sum = dynScore.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) sum = 1.0;
        Map<String, Integer> bytes = new HashMap<>();
        for (var e : dynScore.entrySet()) {
            int alloc = (int) Math.max(props.getSynthesis().getMinBytesPerItem(),
                    (e.getValue()/sum) * maxBytes);
            bytes.put(e.getKey(), alloc);
        }
        return bytes;
    }

    private static long simHash(String s) {
        int n = 3;
        int[] v = new int[64];
        for (int i = 0; i <= s.length()-n; i++) {
            String g = s.substring(i, i+n);
            long h = murmur64(g);
            for (int b = 0; b < 64; b++) v[b] += ((h >>> b) & 1L) == 1L ? 1 : -1;
        }
        long out = 0L;
        for (int b = 0; b < 64; b++) if (v[b] >= 0) out |= (1L << b);
        return out;
    }
    private static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }
    private static long murmur64(String s) {
        long h = 0xc6a4a7935bd1e995L ^ (s.length() * 0xe6546b64L);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b; h *= 0x5bd1e9955bd1e995L; h ^= (h >>> 47);
        }
        return h;
    }
}