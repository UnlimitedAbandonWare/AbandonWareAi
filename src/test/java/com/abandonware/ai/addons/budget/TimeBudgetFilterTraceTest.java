package com.abandonware.ai.addons.budget;

import com.abandonware.ai.addons.config.AddonsProperties;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeBudgetFilterTraceTest {

    @AfterEach
    void clear() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @Test
    void invalidBudgetHeaderFallsBackAndLeavesTypeOnlyTrace() throws Exception {
        AddonsProperties props = new AddonsProperties();
        props.getBudget().setDefaultMs(250);
        TimeBudgetFilter filter = new TimeBudgetFilter(props);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Budget-Ms", "raw private budget token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> {
            invoked.set(true);
            assertNotNull(TimeBudgetContext.get());
            assertTrue(TimeBudgetContext.get().remainingMillis() <= 250);
        });

        assertTrue(invoked.get());
        assertNull(TimeBudgetContext.get());
        assertEquals(1L, TraceStore.get("timeBudget.header.parseFallback.count"));
        assertEquals("invalid_number", TraceStore.get("timeBudget.header.parseFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private budget token"));
    }
}
