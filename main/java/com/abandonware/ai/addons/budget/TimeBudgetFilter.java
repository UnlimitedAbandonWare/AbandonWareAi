package com.abandonware.ai.addons.budget;

import com.abandonware.ai.addons.config.AddonsProperties;
import com.example.lms.search.TraceStore;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;




/** 요청 단위 예산 주입: X-Budget-Ms 헤더 없으면 기본값 사용 */
public class TimeBudgetFilter extends OncePerRequestFilter {
    private static final long HARD_MAX_BUDGET_MS = 3_600_000L;
    private final AddonsProperties props;
    public TimeBudgetFilter(AddonsProperties props) { this.props = props; }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        TimeBudget existing = TimeBudgetContext.get();
        boolean ownsContext = existing == null;
        if (ownsContext) {
            String h = req.getHeader("X-Budget-Ms");
            long serverMaxMs = Math.max(
                    1L,
                    Math.min(HARD_MAX_BUDGET_MS, props.getBudget().getDefaultMs()));
            long ms = serverMaxMs;
            if (h != null) try {
                long requested = Long.parseLong(h.trim());
                ms = Math.max(1L, Math.min(requested, serverMaxMs));
                if (requested <= 0L || requested > serverMaxMs) {
                    TraceStore.inc("timeBudget.header.clamped.count");
                    TraceStore.put("timeBudget.header.clamped", true);
                }
            } catch (NumberFormatException ex) {
                TraceStore.inc("timeBudget.header.parseFallback.count");
                TraceStore.put("timeBudget.header.parseFallback.errorType", "invalid_number");
            }
            TimeBudgetContext.set(new TimeBudget(ms));
        } else {
            TraceStore.put("timeBudget.context.reused", true);
            if (existing.expired()) {
                TraceStore.put("timeBudget.terminalReason", "request_deadline_exhausted");
                res.setStatus(HttpServletResponse.SC_REQUEST_TIMEOUT);
                res.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
                res.setContentType("application/problem+json");
                res.getWriter().write(
                        "{\"status\":408,\"reasonCode\":\"request_deadline_exhausted\"}");
                return;
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            if (ownsContext) TimeBudgetContext.clear();
        }
    }
}
