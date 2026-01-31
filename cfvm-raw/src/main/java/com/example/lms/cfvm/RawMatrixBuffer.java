package com.example.lms.cfvm;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Condensed-Fusion Virtual Matrix (CFVM-Raw) buffer.
 * Maintains a rolling window of RawSlots and exposes a 3x3 weight matrix
 * updated via a simple Boltzmann-like transform on slot frequencies.
 */
public final class RawMatrixBuffer {

    private final int capacity;
    private final Deque<RawSlot> ring;
    // 3x3 matrix (nine virtual tiles)
    private final double[][] w = new double[3][3];

    public RawMatrixBuffer(int capacity) {
        this.capacity = Math.max(32, capacity);
        this.ring = new ConcurrentLinkedDeque<>();
        zero();
    }

    public synchronized void push(RawSlot slot) {
        ring.addLast(slot);
        while (ring.size() > capacity) {
            ring.removeFirst();
        }
    }

    public synchronized List<RawSlot> snapshot() {
        return List.copyOf(ring);
    }

    public synchronized double[][] weights() {
        // defensive copy
        double[][] out = new double[3][3];
        for (int i=0;i<3;i++) System.arraycopy(w[i], 0, out[i], 0, 3);
        return out;
    }

    public synchronized void zero() {
        for (int i=0;i<3;i++) Arrays.fill(w[i], 0.0);
    }

    /**
     * Recompute the 3x3 matrix from the current ring content using:
     *  - severity mapping to row (INFO/WARN/ERROR -> 0/1/2)
     *  - hash of code to column (0..2)
     *  - frequency accumulation with exponential decay
     */
    public synchronized void fit(double decay) {
        zero();
        final double alpha = Math.min(Math.max(decay, 0.80), 0.999); // keep memory
        Map<String, Integer> codeCount = new HashMap<>();
        for (RawSlot s : ring) {
            codeCount.merge(s.code(), 1, Integer::sum);
        }
        for (RawSlot s : ring) {
            int row = switch (s.severity()) {
                case INFO -> 0;
                case WARN -> 1;
                case ERROR -> 2;
            };
            int col = Math.floorMod(s.code().hashCode(), 3);
            double base = codeCount.getOrDefault(s.code(), 1);
            w[row][col] = alpha * w[row][col] + (1.0 - alpha) * base;
        }
    }

    /** Temperature-scaled softmax across all 9 cells; returns normalized matrix. */
    public synchronized double[][] boltzmann(double temperature) {
        double T = Math.max(1e-3, temperature);
        double[] flat = new double[9];
        int k = 0;
        for (int i=0;i<3;i++)
            for (int j=0;j<3;j++)
                flat[k++] = w[i][j];

        double max = Arrays.stream(flat).max().orElse(0.0);
        double sum = 0.0;
        for (int i=0;i<flat.length;i++) {
            flat[i] = Math.exp((flat[i] - max) / T);
            sum += flat[i];
        }
        if (sum <= 0) sum = 1.0;
        for (int i=0;i<flat.length;i++) flat[i] /= sum;

        double[][] out = new double[3][3];
        k = 0;
        for (int i=0;i<3;i++)
            for (int j=0;j<3;j++)
                out[i][j] = flat[k++];
        return out;
    }
}