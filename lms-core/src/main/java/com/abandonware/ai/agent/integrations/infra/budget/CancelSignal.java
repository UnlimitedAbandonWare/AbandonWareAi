package com.abandonware.ai.agent.integrations.infra.budget;


import java.util.concurrent.atomic.AtomicBoolean;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.infra.budget.CancelSignal
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.infra.budget.CancelSignal
role: config
*/
public class CancelSignal {
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    public void cancel(){ canceled.set(true); }
    public boolean isCanceled(){ return canceled.get(); }
}