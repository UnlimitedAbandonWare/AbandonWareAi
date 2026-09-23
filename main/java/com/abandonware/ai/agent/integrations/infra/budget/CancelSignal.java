package com.abandonware.ai.agent.integrations.infra.budget;


import java.util.concurrent.atomic.AtomicBoolean;
/**
 * CancelSignal: propagates cancellation.
 */
public class CancelSignal {
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    public void cancel(){ canceled.set(true); }
    public boolean isCanceled(){ return canceled.get(); }
}