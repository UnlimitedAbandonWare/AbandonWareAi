
package com.example.lms.fusion;

import org.springframework.stereotype.Component;

import java.util.*;



@Component("fusionMatrixTransformer")
public class MatrixTransformer {
    public static class Item {
        public final String id; public final String title; public final String snippet; public final String source;
        public double score; public int rank; public double contribScore;
        public Item(String id, String title, String snippet, String source, double score, int rank) {
            this.id=id; this.title=title; this.snippet=snippet; this.source=source; this.score=score; this.rank=rank;
        }
    }

    /** Allocate line budget by normalized contribution. Guarantees a minimum per source. */
    public static Map<String,Integer> allocateLines(List<Item> items, int total, int minPerSource) {
        Map<String,Double> contrib = new HashMap<>();
        for (Item it: items) {
            double c = it.score * (1.0 / (1+it.rank));
            it.contribScore = c;
            contrib.put(it.source, contrib.getOrDefault(it.source,0.0)+c);
        }
        double sum = contrib.values().stream().mapToDouble(d->d).sum();
        Map<String,Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String,Double> e: contrib.entrySet()) {
            int alloc = (int)Math.round(total * (sum>0? e.getValue()/sum : 1.0/contrib.size()));
            out.put(e.getKey(), Math.max(minPerSource, alloc));
        }
        // normalize to total
        int s = out.values().stream().mapToInt(i->i).sum();
        while (s>total) {
            String maxK = out.entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue)).get().getKey();
            out.put(maxK, out.get(maxK)-1); s--;
        }
        while (s<total) {
            String minK = out.entrySet().stream().min(Comparator.comparingInt(Map.Entry::getValue)).get().getKey();
            out.put(minK, out.get(minK)+1); s++;
        }
        return out;
    }
}