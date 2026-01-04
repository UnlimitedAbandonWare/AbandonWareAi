package com.example.lms.nova;

import java.lang.reflect.Method;



public final class NovaPlanApplier {
    private NovaPlanApplier() {}

    public static <T> T apply(T planObj, BravePlan plan) {
        if (planObj == null || plan == null || !plan.enabled) return planObj;
        trySet(planObj, "withWebTopK", int.class, plan.webTopK);
        trySet(planObj, "withVectorTopK", int.class, plan.vectorTopK);
        trySet(planObj, "withKgTopK", int.class, plan.kgTopK);
        trySet(planObj, "withMinCitations", int.class, plan.minCitations);
        trySet(planObj, "withBurst", int.class, int.class, plan.burstMin, plan.burstMax);
        return planObj;
    }

    public static <T> T overlayTopK(T options, int webTopK) {
        trySet(options, "setWebTopK", int.class, webTopK);
        return options;
    }

    private static void trySet(Object obj, String method, Class<?> argType, Object value) {
        try {
            Method m = obj.getClass().getMethod(method, argType);
            m.invoke(obj, value);
        } catch (Exception ignored) {}
    }

    private static void trySet(Object obj, String method, Class<?> arg1, Class<?> arg2, Object v1, Object v2) {
        try {
            Method m = obj.getClass().getMethod(method, arg1, arg2);
            m.invoke(obj, v1, v2);
        } catch (Exception ignored) {}
    }
}