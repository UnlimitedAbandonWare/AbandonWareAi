package com.abandonware.ai.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FederatedEmbeddingStore {

    private final LocalEmbeddingStore local = new LocalEmbeddingStore();
    private final Map<String, LocalEmbeddingStore> remotes = new HashMap<>();

    @Value("${retrieval.vector.enabled:true}") private boolean enabled;

    public void put(String id, float[] vec, Map<String,String> meta){ local.put(id, vec, meta); }

    public List<LocalEmbeddingStore.Result> search(float[] q, int topK){
        if (!enabled) return java.util.List.of();
        List<LocalEmbeddingStore.Result> base = local.search(q, topK);
        for (var es: remotes.values()){
            try {
                var r = es.search(q, Math.max(3, topK/2));
                base = fuseRrf(base, r, topK);
            } catch (Throwable ignore){}
        }
        return base;
    }

    private List<LocalEmbeddingStore.Result> fuseRrf(List<LocalEmbeddingStore.Result> a, List<LocalEmbeddingStore.Result> b, int topK){
        Map<String, Double> score = new HashMap<>();
        int k = 60, r = 1;
        for (var list : java.util.List.of(a,b)){
            for (int i=0;i<list.size();i++){
                var id = list.get(i).id();
                score.merge(id, 1.0/(k+i+r), Double::sum);
            }
        }
        return score.entrySet().stream()
            .sorted((x,y)->Double.compare(y.getValue(), x.getValue()))
            .limit(topK)
            .map(e -> new LocalEmbeddingStore.Result(e.getKey(), e.getValue(), java.util.Map.of()))
            .toList();
    }
}