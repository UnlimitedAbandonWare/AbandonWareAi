package com.nova.protocol.rerank;
import java.util.*;
public final class Dpp {
    public static <T> List<T> select(List<T> input, int k, double sigma){
        if (input == null) return Collections.emptyList();
        int kk = Math.max(0, Math.min(k, input.size()));
        return new ArrayList<>(input.subList(0, kk));
    }
}