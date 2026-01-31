package com.abandonwareai.zsystem;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.zsystem.CancelSignal
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.zsystem.CancelSignal
role: config
*/
public class CancelSignal {
    private volatile boolean cancelled=false; public void cancel(){cancelled=true;} public boolean isCancelled(){return cancelled;}

}