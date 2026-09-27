package com.example.lms.api;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ChatWaitAdmissionFocusedTest {
    @Test void selectedModelWaitFitsBoundedDefaultAdmission() throws Exception {
        var guard = new PublicRequestBudgetGuard();
        var request = request(240000);
        var response = new MockHttpServletResponse();
        AtomicBoolean entered = new AtomicBoolean();
        try {
            guard.doFilter(request, response, (req, res) -> {
                entered.set(true);
                assertTrue(TimeBudgetContext.get().remainingMillis() > 180000);
                assertTrue(TimeBudgetContext.get().remainingMillis() <= 240000);
            });
            assertTrue(entered.get());
            assertEquals(200, response.getStatus());
            assertNull(TimeBudgetContext.get());
        } finally { guard.shutdownBodyReadExecutor(); TraceStore.clear(); }
    }

    @Test void operatorCapStillRejectsLongerClientWait() throws Exception {
        var guard = new PublicRequestBudgetGuard();
        guard.setMaxTimeBudgetMs(30000);
        var response = new MockHttpServletResponse();
        try {
            guard.doFilter(request(240000), response, (req, res) -> fail("must reject before dispatch"));
            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString().contains("public_time_budget_invalid"));
        } finally { guard.shutdownBodyReadExecutor(); TraceStore.clear(); }
    }
    private MockHttpServletRequest request(long budget) {
        var request = new MockHttpServletRequest("POST", "/api/chat/stream");
        request.addHeader("X-Budget-Ms", Long.toString(budget));
        request.setContentType("application/json");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
