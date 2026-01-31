package com.abandonware.ai.service.rag.whiten;

public interface Whitening {
    boolean isEnabled();
    float[] apply(float[] x);

    default float[] maybeApply(float[] x){
        try { return isEnabled() ? apply(x) : x; } catch (Throwable t){ return x; }
    }
}