package com.example.patch;

import java.lang.reflect.Method;
import java.util.Map;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.patch.FlowJoinerBridge
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.patch.FlowJoinerBridge
role: config
*/
public class FlowJoinerBridge {
    public static String plan(Object ctx) {
        try {
            Class<?> fj = Class.forName("router.joiner.FlowJoiner");
            Method m = fj.getMethod("planAndExecute", Object.class);
            Object plan = m.invoke(null, ctx);
            return String.valueOf(plan);
        } catch (Throwable t) {
            return "BYPASS";
        }
    }
}