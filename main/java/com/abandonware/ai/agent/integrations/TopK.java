
package com.abandonware.ai.agent.integrations;

import java.util.*;



public class TopK<T> {
    private final int k;
    private final PriorityQueue<Item<T>> pq;

    public TopK(int k) {
        this.k = Math.max(0, k);
        this.pq = new PriorityQueue<>(Comparator.comparingDouble(i -> i.score)); // min-heap; evict lowest score
    }

    public void add(T value, double score) {
        pq.add(new Item<>(value, score));
        while (pq.size() > k) pq.poll();
    }

    public List<Item<T>> toListSortedDesc() {
        List<Item<T>> list = new ArrayList<>(pq);
        list.sort((a,b)-> Double.compare(b.score, a.score));
        return list;
    }

    public static class Item<T> {
        public final T value; public final double score;
        public Item(T value, double score){this.value=value; this.score=score;}
    }
}
