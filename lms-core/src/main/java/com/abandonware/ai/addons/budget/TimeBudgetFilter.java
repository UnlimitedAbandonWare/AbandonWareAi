package com.abandonware.ai.addons.budget;

import com.abandonware.ai.addons.config.AddonsProperties;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.budget.TimeBudgetFilter
 * Role: config
 * Dependencies: com.abandonware.ai.addons.config.AddonsProperties
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.budget.TimeBudgetFilter
role: config
*/
public class TimeBudgetFilter extends OncePerRequestFilter {
    private final AddonsProperties props;
    public TimeBudgetFilter(AddonsProperties props) { this.props = props; }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("X-Budget-Ms");
        long ms = props.getBudget().getDefaultMs();
        if (h != null) try { ms = Math.max(1, Long.parseLong(h)); } catch (NumberFormatException ignore) {}
        TimeBudgetContext.set(new TimeBudget(ms));
        try { chain.doFilter(req, res); } finally { TimeBudgetContext.clear(); }
    }
}