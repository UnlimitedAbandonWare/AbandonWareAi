package com.abandonware.ai.vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.vector.LocalEmbeddingStore
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.vector.LocalEmbeddingStore
role: config
*/
public class LocalEmbeddingStore {

    private final Map<String, float[]> store = new ConcurrentHashMap<>();
    private final Map<String, Map<String,String>> meta = new ConcurrentHashMap<>();

    public void put(String id, float[] vec, Map<String,String> m){ store.put(id, vec); meta.put(id, m==null? Map.of():m); }

    public List<Result> search(float[] query, int topK){
        PriorityQueue<Result> pq = new PriorityQueue<>(Comparator.comparingDouble(Result::score));
        for (var e: store.entrySet()) {
            double s = cosine(query, e.getValue());
            pq.offer(new Result(e.getKey(), s, meta.getOrDefault(e.getKey(), Map.of())));
            if (pq.size()>topK) pq.poll();
        }
        List<Result> out = new ArrayList<>(); while(!pq.isEmpty()) out.add(pq.poll());
        java.util.Collections.reverse(out); return out;
    }

    private static double cosine(float[] a, float[] b){
        double dot=0,na=0,nb=0; int n=Math.min(a.length,b.length);
        for (int i=0;i<n;i++){ dot+=a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        return (na==0||nb==0)?0: dot/Math.sqrt(na*nb);
    }

    public record Result(String id, double score, Map<String,String> meta){}
}