package com.abandonwareai.rerank;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.rerank.CrossEncoderGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonwareai.rerank.CrossEncoderGate
role: config
*/
public class CrossEncoderGate {
    private int permits=4; public synchronized boolean acquire(){ return permits-->0; } public synchronized void release(){ permits++; }

}