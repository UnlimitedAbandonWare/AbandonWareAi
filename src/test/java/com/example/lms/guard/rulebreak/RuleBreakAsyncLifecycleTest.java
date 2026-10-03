package com.example.lms.guard.rulebreak;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.web.TraceFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleBreakAsyncLifecycleTest {
    @AfterEach void cleanup() { RuleBreakContextHolder.clear(); }
    private static RuleBreakContext active() {
        return RuleBreakContext.active(RuleBreakPolicy.SAFE_EXPLORE, "synthetic-hash",
                Instant.now().plusSeconds(60), "request-a", "session-a");
    }
    @SuppressWarnings("unchecked")
    private static RuleBreakInterceptor interceptor() {
        return new RuleBreakInterceptor(null, (ObjectProvider<DebugEventStore>) mock(ObjectProvider.class));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void synchronousCompletionClearsOnOriginalThread(boolean failure) {
        RuleBreakContextHolder.set(active());
        interceptor().afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new Object(), failure ? new Exception("synthetic") : null);
        assertNull(RuleBreakContextHolder.get());
    }
    @Test void asyncStartClearsBeforeTimeoutOrDisconnectCanSkipRedispatch() throws Exception {
        RuleBreakContextHolder.set(active());
        AsyncHandlerInterceptor async = assertInstanceOf(AsyncHandlerInterceptor.class, interceptor());
        async.afterConcurrentHandlingStarted(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());
        assertNull(RuleBreakContextHolder.get());
    }
    @Test void redispatchWithoutHeaderClearsOldContext() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setDispatcherType(DispatcherType.ASYNC);
        RuleBreakContextHolder.set(active());
        assertTrue(interceptor().preHandle(req,new MockHttpServletResponse(),new Object()));
        assertNull(RuleBreakContextHolder.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void filterFinallyClearsEvenWithoutMvcCompletion(boolean failure) throws Exception {
        TraceFilter filter = new TraceFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/chat/stream");
        var response = new MockHttpServletResponse();
        if (failure) {
            assertThrows(ServletException.class, () -> filter.doFilter(req,response,(r,s)->{
                RuleBreakContextHolder.set(active());
                throw new ServletException("synthetic");
            }));
        } else {
            filter.doFilter(req,response,(r,s)->RuleBreakContextHolder.set(active()));
        }
        assertNull(RuleBreakContextHolder.get(), "request thread must be clean before reuse");
        filter.doFilter(new MockHttpServletRequest("GET","/chat"), new MockHttpServletResponse(),
                (r,s)->assertNull(RuleBreakContextHolder.get()));
    }
}
