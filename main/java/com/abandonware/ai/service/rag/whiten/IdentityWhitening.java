package com.abandonware.ai.service.rag.whiten;

public final class IdentityWhitening implements Whitening {
    @Override public boolean isEnabled(){ return false; }
    @Override public float[] apply(float[] x){ return x; }
}