package com.nova.protocol.alloc;
public interface RiskKAllocator {
    int[] alloc(double[] logits, double[] risk, int totalK, double temp, int[] floor);
}