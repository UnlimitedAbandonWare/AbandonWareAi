package com.abandonwareai.guard;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.guard.ReflectionProxyGuard
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.guard.ReflectionProxyGuard
role: config
*/
public class ReflectionProxyGuard {
    public boolean allow(Class<?> c){ return !c.getName().contains("java.lang.reflect.Proxy"); }

}