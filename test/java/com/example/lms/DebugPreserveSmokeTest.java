// src/test/java/com/example/lms/DebugPreserveSmokeTest.java
package com.example.lms;

import com.example.lms.debug.PromptDebugLogger;
import com.example.lms.trace.WebClientDiagnostics;
import com.example.lms.trace.RequestIdHeaderFilter;
import com.example.lms.web.TraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;



import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "lms.trace.enabled=true",
        "lms.trace.http.enabled=false",
        "lms.debug.enabled=true",
        "lms.debug.dump-prompts=false"
})
class DebugPreserveSmokeTest {
    @Autowired ApplicationContext ctx;

    @Test void coreBeansPresent() {
        assertNotNull(ctx.getBean(TraceFilter.class));
        assertNotNull(ctx.getBean(RequestIdHeaderFilter.class));
        assertNotNull(ctx.getBean(PromptDebugLogger.class));
    }
}