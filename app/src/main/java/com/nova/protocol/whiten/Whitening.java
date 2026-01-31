package com.nova.protocol.whiten;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.whiten.Whitening
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.whiten.Whitening
role: config
*/
public interface Whitening { float[] apply(float[] vec); }