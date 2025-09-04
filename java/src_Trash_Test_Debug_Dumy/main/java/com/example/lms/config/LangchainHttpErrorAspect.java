package com.example.lms.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Aspect that intercepts calls to LangChain4j chat models and logs the
 * underlying exception bodies when available.  In practice the
 * OpenAI/Groq clients may wrap HTTP error responses in exceptions
 * containing the response body.  This aspect introspects common
 * methods such as getBody() and logs the value when an exception
 * propagates.  The exception is then rethrown to preserve existing
 * behaviour.
 */
@Aspect
@Component
public class LangchainHttpErrorAspect {

    private static final Logger log = LoggerFactory.getLogger("LC4J.ERROR");

    @Around("execution(* dev.langchain4j.model.chat.ChatModel.chat(..))")
    public Object aroundChat(ProceedingJoinPoint pjp) throws Throwable {
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            logErrorBodyIfAny(t);
            throw t;
        }
    }

    private void logErrorBodyIfAny(Throwable t) {
        Throwable c = t;
        // Drill down the cause chain to the root cause
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        // Attempt to call common body accessor methods via reflection
        for (String m : new String[]{"getBody", "body", "getResponseBody", "responseBody"}) {
            try {
                java.lang.reflect.Method mm = c.getClass().getMethod(m);
                mm.setAccessible(true);
                Object body = mm.invoke(c);
                if (body != null) {
                    String s = String.valueOf(body);
                    if (!s.isBlank()) {
                        log.warn("LangChain4j HTTP error body: {}", truncate(s));
                        return;
                    }
                }
            } catch (Throwable ignore) {
                // ignore missing methods
            }
        }
        // Fallback to logging the exception itself when no body was found
        log.warn("LangChain4j error: {}", t.toString(), t);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        int max = 4000;
        return s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
    }
}