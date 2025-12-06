
package com.abandonware.ai.agent.rag.fusion;
import com.abandonware.ai.agent.rag.model.Result;
import java.util.*;
import java.util.stream.Collectors;

public class WeightedRRF {
    private List<Double> weights = Arrays.asList(1.0, 1.0, 1.0); // web, vector, kg (default)

    public void setWeights(List<Double> w) {
        if (w!=null && w.size()>=3) this.weights = w;
    }

    public List<Result> merge(List<Result> web, List<Result> vector, List<Result> kg) {
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, Result> any = new HashMap<>();
        List<List<Result>> lists = Arrays.asList(web, vector, kg);
        for (int i=0;i<lists.size();i++) {
            List<Result> list = lists.get(i);
            double w = i<weights.size()?weights.get(i):1.0;
            for (int r=0;r< (list==null?0:list.size()); r++) {
                Result res = list.get(r);
                String key = res.getId()!=null? res.getId() : (res.getTitle()+"|"+res.getSource());
                any.putIfAbsent(key, res);
                double rrf = 1.0 / (60.0 + (r+1)); // RRF base
                scoreMap.put(key, scoreMap.getOrDefault(key, 0.0) + w * rrf);
            }
        }
        List<Map.Entry<String,Double>> sorted = new ArrayList<>(scoreMap.entrySet());
        sorted.sort((a,b)-> Double.compare(b.getValue(), a.getValue()));
        List<Result> out = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String,Double> e: sorted) {
            Result src = any.get(e.getKey());
            Result r = new Result(src.getId(), src.getTitle(), src.getSnippet(), src.getSource(), e.getValue(), rank++);
            out.add(r);
        }
        return out;
    }
}
