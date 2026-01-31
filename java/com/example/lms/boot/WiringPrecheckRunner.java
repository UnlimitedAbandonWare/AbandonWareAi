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

        // Key/Provider diagnostics (no secrets) - helps confirm env cleanup and alias wiring.
        try {
            String braveEnabled = ctx.getEnvironment().getProperty("gpt-search.brave.enabled", "true");
            String braveConflict = ctx.getEnvironment().getProperty("nova.provider.brave.key.conflict", "false");
            String braveWinnerSource = ctx.getEnvironment().getProperty("nova.provider.brave.key.winnerSource", "");
            String braveSources = ctx.getEnvironment().getProperty("nova.provider.brave.key.sources", "");
            if (!braveSources.isBlank()) {
                log.info("[Precheck] BraveKey: enabled={}, conflict={}, winnerSource={}, sources={}",
                        braveEnabled, braveConflict, braveWinnerSource, braveSources);
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        // Tuning knobs snapshot (avoid hunting through scattered config when debugging).
        try {
            String minLive = ctx.getEnvironment().getProperty("gpt-search.hybrid.await.min-live-budget-ms", "600");
            String fullJoin = ctx.getEnvironment().getProperty(
                    "gpt-search.hybrid.official-only.brave-full-join.max-wait-ms",
                    "2000");
            log.info("[Precheck] HybridAwait: await.min-live-budget-ms={}, officialOnly.brave-full-join.max-wait-ms={}",
                    minLive, fullJoin);
        } catch (Throwable ignore) {
            // fail-soft
        }

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
