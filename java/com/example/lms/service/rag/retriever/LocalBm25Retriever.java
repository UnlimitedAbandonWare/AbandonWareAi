package com.example.lms.service.rag.retriever;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal dependency-free BM25-like scorer (placeholder).
 *  Toggle: retrieval.localbm25.enabled
 *  This is a light in-memory index for small corpora or tests.
 */
public class LocalBm25Retriever {

    public static class Doc {
        public final String id;
        public final String text;
        public Doc(String id, String text){ this.id=id; this.text=text==null?"":text; }
    }

    private final Map<String, Integer> df = new ConcurrentHashMap<>();
    private final List<Doc> docs = Collections.synchronizedList(new ArrayList<>());
    private double avgLen = 0d;

    public void add(Doc d){
        docs.add(d);
        String[] terms = tokenize(d.text);
        Set<String> seen = new HashSet<>();
        for (String t: terms){
            if(seen.add(t)){
                df.merge(t, 1, Integer::sum);
            }
        }
        avgLen = docs.stream().mapToInt(x -> tokenize(x.text).length).average().orElse(1d);
    }

    public List<Doc> topK(String query, int k){
        String[] q = tokenize(query);
        double N = Math.max(1, docs.size());
        List<Scored> scored = new ArrayList<>();
        for (Doc d: docs){
            double score = 0d;
            String[] terms = tokenize(d.text);
            Map<String,Integer> tf = new HashMap<>();
            for(String t: terms) tf.merge(t,1,Integer::sum);
            double dl = Math.max(1, terms.length);
            for(String term: q){
                int ni = df.getOrDefault(term, 0);
                if(ni==0) continue;
                double idf = Math.log( (N - ni + 0.5) / (ni + 0.5) + 1e-6 );
                double tfv = tf.getOrDefault(term, 0);
                double k1 = 1.2, b = 0.75;
                double denom = tfv + k1 * (1 - b + b * (dl / Math.max(1, avgLen)));
                score += idf * ((tfv * (k1+1)) / Math.max(1e-6, denom));
            }
            if(score>0) scored.add(new Scored(d, score));
        }
        scored.sort((a,b)->Double.compare(b.s, a.s));
        List<Doc> out = new ArrayList<>();
        for(int i=0;i<Math.min(k, scored.size());i++) out.add(scored.get(i).d);
        return out;
    }

    private static class Scored{ Doc d; double s; Scored(Doc d,double s){this.d=d;this.s=s;} }
    private String[] tokenize(String t){
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9가-힣 ]"," ").split("\\s+"); }
}