package com.example.lms.service.rag.handler.aop;

import com.example.lms.search.TraceStore;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Aspect that inspects VectorDbHandler results and publishes link metadata
 * into the TraceStore using standard keys: links.vector.urls, titles, scores.
 */
@Aspect
@Component
@Slf4j
public class VectorLinkTraceAspect {

    @Around("execution(* com.example.lms.service.rag.handler.*VectorDbHandler*.*(..))")
    public Object capture(ProceedingJoinPoint pjp) throws Throwable {
        Object res = pjp.proceed();
        try {
            if (res instanceof java.util.List<?>) {
                List<?> list = (List<?>) res;
                List<String> urls = new ArrayList<>();
                List<String> titles = new ArrayList<>();
                List<Double> scores = new ArrayList<>();
                for (Object o : list) {
                    if (o == null) continue;
                    String u = invokeString(o, "getUrl", "url"); 
                    String t = invokeString(o, "getTitle", "title"); 
                    Double s = invokeDouble(o, "getScore", "score"); 
                    if (u != null) { urls.add(u); }
                    if (t != null) { titles.add(t); } else { titles.add(null); }
                    if (s != null) { scores.add(s); } else { scores.add(null); }
                }
                if (!urls.isEmpty()) {
                    TraceStore.put("links.vector.urls", urls);
                    TraceStore.put("links.vector.titles", titles);
                    TraceStore.put("links.vector.scores", scores);
                }
            }
        } catch (Throwable t) {
            log.debug("VectorLinkTraceAspect capture skipped: {}", t.toString());
        }
        return res;
    }

    private static String invokeString(Object o, String name) {
    try {
        java.lang.reflect.Method m = o.getClass().getMethod(name);
        Object v = m.invoke(o);
        if (v == null) return null;
        return String.valueOf(v);
    } catch (Exception ignored) {}
    return null;
}
private static String invokeString(Object o, String... names) {
    for (String n : names) {
        String v = invokeString(o, n);
        if (v != null && !v.isBlank()) return v;
    }
    return null;
}
    private static Double invokeDouble(Object o, String name) {
    try {
        java.lang.reflect.Method m = o.getClass().getMethod(name);
        Object v = m.invoke(o);
        if (v == null) return null;
        if (v instanceof Number num) return num.doubleValue();
        if (v != null) return Double.valueOf(v.toString());
    } catch (Exception ignored) {}
    return null;
}
private static Double invokeDouble(Object o, String... names) {
    for (String n : names) {
        Double v = invokeDouble(o, n);
        if (v != null) return v;
    }
    return null;
}
}
