package com.abandonware.ai.addons.budget;

import java.util.concurrent.atomic.AtomicBoolean;



public final class CancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() {
        var tb = TimeBudgetContext.get();
        return cancelled.get() || (tb != null && tb.expired());
    }
    public void throwIfCancelled() {
        if (isCancelled()) throw new RuntimeException("Cancelled/Expired by budget");
    }
}