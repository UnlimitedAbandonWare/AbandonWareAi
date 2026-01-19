package com.example.lms.boot;

import ai.abandonware.nova.boot.exec.CancelShieldExecutorServicePostProcessor;
import ai.abandonware.nova.orch.aop.HybridWebSearchEmptyFallbackAspect;
import ai.abandonware.nova.orch.aop.WebFailSoftSearchAspect;
import com.example.lms.config.ModelGuard;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.trace.RequestIdHeaderFilter;
import com.example.lms.web.TraceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Lightweight startup-time wiring precheck.
 *
 * <p>Goal: surface missing / duplicated critical beans early with explicit logs,
 * without crashing the app in production unless absolutely necessary.</p>
 */
@Component
public class WiringPrecheckRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WiringPrecheckRunner.class);

    private final ApplicationContext ctx;

    public WiringPrecheckRunner(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean enabled = Boolean.parseBoolean(ctx.getEnvironment().getProperty(
                "nova.ops.precheck.enabled",
                "true"
        ));
        if (!enabled) {
            log.info("[Precheck] disabled (nova.ops.precheck.enabled=false)");
            return;
        }

        log.info("[Precheck] starting…");

        // Core runtime pieces (expected to exist)
        checkSingle("PromptBuilder", PromptBuilder.class, true);
        checkSingle("HybridWebSearchProvider", HybridWebSearchProvider.class, true);

        // Debug/correlation (nice-to-have, but operationally critical for incident response)
        checkSingle("RequestIdHeaderFilter", RequestIdHeaderFilter.class, false);
        checkSingle("TraceFilter", TraceFilter.class, false);

        // Guards / validation
        checkSingle("ModelGuard", ModelGuard.class, false);

        // Ops hardening (expected via AutoConfiguration)
        checkSingle("WebFailSoftSearchAspect", WebFailSoftSearchAspect.class, false);
        checkSingle("HybridWebSearchEmptyFallbackAspect", HybridWebSearchEmptyFallbackAspect.class, false);
        checkSingle("CancelShieldExecutorServicePostProcessor", CancelShieldExecutorServicePostProcessor.class, false);

        log.info("[Precheck] done.");
    }

    private <T> void checkSingle(String label, Class<T> type, boolean required) {
        Map<String, T> beans = ctx.getBeansOfType(type);
        int n = (beans == null) ? 0 : beans.size();

        if (n == 1) {
            String name = beans.keySet().iterator().next();
            log.info("[Precheck] OK: {} -> bean='{}' ({})", label, name, type.getName());
            return;
        }

        if (n == 0) {
            if (required) {
                log.error("[Precheck] MISSING: {} ({}) - app may not function as expected", label, type.getName());
            } else {
                log.warn("[Precheck] missing: {} ({})", label, type.getName());
            }
            return;
        }

        // n > 1
        log.warn("[Precheck] MULTIPLE: {} ({}) -> {}", label, type.getName(), beans.keySet());
    }
}
