package com.example.lms.learning.virtualpoint;

import java.util.Arrays;

public class VirtualPoint {
    public final float[] vector;
    public VirtualPoint(float[] v) {
        this.vector = v == null ? new float[0] : Arrays.copyOf(v, v.length);
    }
}