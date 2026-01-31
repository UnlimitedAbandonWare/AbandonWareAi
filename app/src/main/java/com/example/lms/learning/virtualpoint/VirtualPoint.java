package com.example.lms.learning.virtualpoint;

import java.util.Arrays;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.learning.virtualpoint.VirtualPoint
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.learning.virtualpoint.VirtualPoint
role: config
*/
public class VirtualPoint {
    public final float[] vector;
    public VirtualPoint(float[] v) {
        this.vector = v == null ? new float[0] : Arrays.copyOf(v, v.length);
    }
}