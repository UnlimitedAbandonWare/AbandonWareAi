package com.example.patch;

import java.lang.reflect.Method;
import java.util.Map;


/** Safe bridge: resolve FlowJoiner via reflection if present. */
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