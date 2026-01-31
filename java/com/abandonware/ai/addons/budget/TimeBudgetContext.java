package com.abandonware.ai.addons.budget;


public final class TimeBudgetContext {
    private static final ThreadLocal<TimeBudget> TL = new ThreadLocal<>();
    public static void set(TimeBudget tb) { TL.set(tb); }
    public static TimeBudget get() { return TL.get(); }
    public static void clear() { TL.remove(); }
    private TimeBudgetContext() {}
}