package com.example.lms.service.service.rag.bm25;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Bm25Retriever {
    private final Bm25Index index;

    public Bm25Retriever(Bm25Index index) {
        this.index = index;
    }

    public List<Result> retrieve(String query, int topK) {
        List<Map.Entry<String, Double>> hits = index.search(query, topK);
        List<Result> out = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Double> hit : hits) {
            out.add(new Result(hit.getKey(), hit.getValue(), rank++));
        }
        return out;
    }

    public static class Result {
        public final String id;
        public final double score;
        public final int rank;

        public Result(String id, double score, int rank) {
            this.id = id;
            this.score = score;
            this.rank = rank;
        }
    }
}
