package com.abandonware.ai.normalization.service.prompt;

import java.util.*;

public class ContextPortfolioAllocator {
    public Map<String,Integer> allocate(Map<String,Double> contribution, int total, int minLines) {
        Map<String,Integer> out = new LinkedHashMap<>();
        double sum = contribution.values().stream().mapToDouble(d->d).sum();
        for (Map.Entry<String,Double> e: contribution.entrySet()) {
            int alloc = (int)Math.round((e.getValue()/Math.max(1e-9,sum)) * total);
            out.put(e.getKey(), Math.max(minLines, alloc));
        }
        return out;
    }
}

public class PromptBuilder {
    public String build(Map<String, List<String>> sources, Map<String, Double> contribution, int totalLines, int minLines) {
        ContextPortfolioAllocator alloc = new ContextPortfolioAllocator();
        Map<String,Integer> lines = alloc.allocate(contribution, totalLines, minLines);
        StringBuilder sb = new StringBuilder();
        sb.append("# Context\n");
        for (Map.Entry<String,Integer> e: lines.entrySet()) {
            String src = e.getKey();
            int k = e.getValue();
            List<String> items = sources.getOrDefault(src, Collections.emptyList());
            for (int i=0;i<Math.min(k, items.size()); i++) {
                sb.append("- ").append(src).append(": ").append(items.get(i)).append("\n");
            }
        }
        return sb.toString();
    }
}