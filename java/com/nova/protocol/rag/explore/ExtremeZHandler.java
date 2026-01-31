package com.nova.protocol.rag.explore;

import java.util.List;
import java.util.stream.Collectors;



public class ExtremeZHandler {
    public List<String> selectRange(List<String> expanded, int low, int high) {
        if (expanded == null || expanded.isEmpty()) return expanded;
        int l = Math.max(0, Math.min(low, expanded.size()-1));
        int h = Math.max(l, Math.min(high, expanded.size()));
        return expanded.subList(l, h).stream().collect(Collectors.toList());
    }
}