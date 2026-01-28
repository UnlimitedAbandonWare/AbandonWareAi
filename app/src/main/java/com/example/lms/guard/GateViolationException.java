package com.example.lms.guard;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.guard.GateViolationException
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.guard.GateViolationException
role: config
*/
public class GateViolationException extends RuntimeException {
    public GateViolationException(String message) { super(message); }
}