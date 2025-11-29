package com.example.lms.service.rag.handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.lms.service.plan.Plan;

/**
 * Minimal chain used by ProbeController: exposes order() and applyPlan().
 * All heavy handlers/types are intentionally omitted to keep compilation green.
 */
public class DynamicRetrievalHandlerChain {
    private final List<String> order = new ArrayList<>();

    public DynamicRetrievalHandlerChain() {
        order.add("web");
        order.add("vector");
        order.add("kg");
    }

    /** Unmodifiable view of current stage order. */
    public List<String> order() {
        return Collections.unmodifiableList(order);
    }

    /** Apply a plan by reordering stages based on provided K allocation. */
    public void applyPlan(Plan p) {
        order.clear();
        if (p == null || p.kAllocation() == null || p.kAllocation().isEmpty()) {
            order.add("web");
            order.add("vector");
            order.add("kg");
            return;
        }
        var k = p.kAllocation();
        if (k.getOrDefault("web", 0) > 0)    order.add("web");
        if (k.getOrDefault("vector", 0) > 0) order.add("vector");
        if (k.getOrDefault("kg", 0) > 0)     order.add("kg");
        if (order.isEmpty()) {
            order.add("web");
        }
    }

    /** Toy DPP-like pass that just keeps the first k distinct items. */
    public <T> List<T> applyDpp(List<T> in, int topK) {
        if (in == null || in.isEmpty()) return List.of();
        int k = Math.min(Math.max(1, topK), in.size());
        List<T> out = new ArrayList<>(k);
        Set<Integer> seen = new HashSet<>();
        for (T t : in) {
            int h = (t == null ? 0 : t.hashCode());
            if (seen.add(h)) {
                out.add(t);
            }
            if (out.size() >= k) break;
        }
        if (out.isEmpty()) out.add(in.get(0));
        return out;
    }
}