
package com.example.lms.cfvm;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;




@Aspect
@Component
public class DecisionTraceAspect {

    @Around("execution(* *..DynamicRetrievalHandlerChain.*(..))")
    public Object trace(ProceedingJoinPoint pjp) throws Throwable {
        long ts = System.currentTimeMillis();
        try {
            Object res = pjp.proceed();
            log("OK", pjp, System.currentTimeMillis()-ts, null);
            return res;
        } catch (Throwable t) {
            log("ERR", pjp, System.currentTimeMillis()-ts, t.toString());
            throw t;
        }
    }

    private void log(String status, ProceedingJoinPoint pjp, long ms, String err) {
        String line = String.format("{\"ts\":%d,\"status\":\"%s\",\"sig\":\"%s\",\"ms\":%d,\"err\":%s}\n",
                Instant.now().toEpochMilli(), status, pjp.getSignature().toShortString(), ms, err==null?"null":"\""+err+"\"");
        try (FileWriter fw = new FileWriter("cfvm-raw/records/trace.ndjson", true)) {
            fw.write(line);
        } catch (IOException ignored) {}
    }
}