package com.abandonware.ai.addons.complexity;


public final class HintsHolder {
    private static final ThreadLocal<RetrievalHints> TL = new ThreadLocal<>();
    public static void set(RetrievalHints h) { TL.set(h); }
    public static RetrievalHints get() { return TL.get(); }
    public static void clear() { TL.remove(); }
    private HintsHolder() {}
}