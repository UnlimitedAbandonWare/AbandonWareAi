package com.abandonware.ai.example.lms.cfvm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;



/** Session-scoped ring buffer holding last N raw slots and 9-way segmentation. */
public final class RawMatrixBuffer {
    private final int capacity;
    private final Deque<RawSlot> ring;

    public RawMatrixBuffer(int capacity) {
        this.capacity = Math.max(9, capacity);
        this.ring = new ArrayDeque<>(this.capacity);
    }

    public synchronized void add(RawSlot slot) {
        if (ring.size() == capacity) ring.removeFirst();
        ring.addLast(slot);
    }

    public synchronized List<RawSlot> snapshot() {
        return new ArrayList<>(ring);
    }

    /** Simple 9-way segmentation by hashing (placeholder for Lissajous projection). */
    public synchronized List<List<RawSlot>> segments9() {
        List<List<RawSlot>> seg = new ArrayList<>(9);
        for (int i=0;i<9;i++) seg.add(new ArrayList<>());
        for (RawSlot s : ring) {
            int idx = Math.floorMod((s.code() + ":" + s.stage()).hashCode(), 9);
            seg.get(idx).add(s);
        }
        return seg;
    }

    /** Boltzmann weighting of each segment, T=temperature. */
    public synchronized double[] boltzmann(double temperature) {
        List<List<RawSlot>> seg = segments9();
        double[] e = new double[9];
        double sum = 0d;
        for (int i=0;i<9;i++) {
            e[i] = Math.exp(seg.get(i).size() / Math.max(1e-6, temperature));
            sum += e[i];
        }
        for (int i=0;i<9;i++) e[i] = sum == 0 ? 0 : e[i]/sum;
        return e;
    }
}