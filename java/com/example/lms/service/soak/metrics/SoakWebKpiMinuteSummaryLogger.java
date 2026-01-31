package com.example.lms.service.soak.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Minute-level summary aggregator for per-request SOAK_WEB_KPI signals.
 *
 * <p>
 * Goal: during soak (5~10min), provide a low-noise rolling view of key stability
 * signals without requiring external log aggregation.
 * </p>
 *
 * <p>
 * It is intentionally <b>fail-soft</b>: any exception is swallowed and never
 * impacts user-facing flows.
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "nova.orch.web-failsoft.soak-kpi-summary", name = "enabled", havingValue = "true")
public class SoakWebKpiMinuteSummaryLogger {

    private static final Logger log = LoggerFactory.getLogger("SOAK_WEB_KPI_SUMMARY");

    private final ObjectMapper om = new ObjectMapper();

    // Config snapshot: helps correlate summary changes with tuning knobs.
    @Value("${gpt-search.hybrid.await.min-live-budget-ms:600}")
    private long awaitMinLiveBudgetMs;

    @Value("${gpt-search.hybrid.official-only.brave-full-join.max-wait-ms:2000}")
    private long officialOnlyBraveFullJoinMaxWaitMs;

    @Value("${nova.orch.web-failsoft.soak-kpi-summary.interval-ms:60000}")
    private long intervalMs;

    @Value("${nova.orch.web-failsoft.soak-kpi-summary.emit-empty-windows:false}")
    private boolean emitEmptyWindows;

    // --- Overall window counters ---
    private final LongAdder total = new LongAdder();
    private final LongAdder sumOutCount = new LongAdder();
    private final LongAdder outZero = new LongAdder();
    private final LongAdder budgetExhausted = new LongAdder();
    private final LongAdder braveCooldown = new LongAdder();
    private final LongAdder braveDisabled = new LongAdder();
    private final LongAdder awaitTimeout = new LongAdder();
    private final LongAdder awaitInterrupted = new LongAdder();

    // --- officialOnly sub-window counters ---
    private final LongAdder officialTotal = new LongAdder();
    private final LongAdder officialSumOutCount = new LongAdder();
    private final LongAdder officialOutZero = new LongAdder();
    private final LongAdder officialBudgetExhausted = new LongAdder();
    private final LongAdder officialBraveCooldown = new LongAdder();
    private final LongAdder officialBraveDisabled = new LongAdder();
    private final LongAdder officialAwaitTimeout = new LongAdder();
    private final LongAdder officialAwaitInterrupted = new LongAdder();

    /**
     * Record a single request outcome.
     */
    public void record(int outCount,
            boolean officialOnly,
            boolean budgetExhaustedFlag,
            boolean braveCooldownFlag,
            boolean braveDisabledFlag,
            boolean awaitTimeoutFlag,
            boolean awaitInterruptedFlag) {

        try {
            int oc = Math.max(0, outCount);
            total.increment();
            sumOutCount.add(oc);
            if (oc == 0) {
                outZero.increment();
            }
            if (budgetExhaustedFlag) {
                budgetExhausted.increment();
            }
            if (braveCooldownFlag) {
                braveCooldown.increment();
            }
            if (braveDisabledFlag) {
                braveDisabled.increment();
            }
            if (awaitTimeoutFlag) {
                awaitTimeout.increment();
            }
            if (awaitInterruptedFlag) {
                awaitInterrupted.increment();
            }

            if (officialOnly) {
                officialTotal.increment();
                officialSumOutCount.add(oc);
                if (oc == 0) {
                    officialOutZero.increment();
                }
                if (budgetExhaustedFlag) {
                    officialBudgetExhausted.increment();
                }
                if (braveCooldownFlag) {
                    officialBraveCooldown.increment();
                }
                if (braveDisabledFlag) {
                    officialBraveDisabled.increment();
                }
                if (awaitTimeoutFlag) {
                    officialAwaitTimeout.increment();
                }
                if (awaitInterruptedFlag) {
                    officialAwaitInterrupted.increment();
                }
            }
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    @Scheduled(
            fixedRateString = "${nova.orch.web-failsoft.soak-kpi-summary.interval-ms:60000}",
            initialDelayString = "${nova.orch.web-failsoft.soak-kpi-summary.initial-delay-ms:65000}")
    public void emitMinuteSummary() {
        try {
            long t = total.sumThenReset();
            long sumOut = sumOutCount.sumThenReset();
            long z = outZero.sumThenReset();
            long be = budgetExhausted.sumThenReset();
            long cd = braveCooldown.sumThenReset();
            long dis = braveDisabled.sumThenReset();
            long at = awaitTimeout.sumThenReset();
            long ai = awaitInterrupted.sumThenReset();

            long ot = officialTotal.sumThenReset();
            long osumOut = officialSumOutCount.sumThenReset();
            long oz = officialOutZero.sumThenReset();
            long obe = officialBudgetExhausted.sumThenReset();
            long ocd = officialBraveCooldown.sumThenReset();
            long odis = officialBraveDisabled.sumThenReset();
            long oat = officialAwaitTimeout.sumThenReset();
            long oai = officialAwaitInterrupted.sumThenReset();

            if (t <= 0 && !emitEmptyWindows) {
                return;
            }

            Map<String, Object> j = new LinkedHashMap<>();
            j.put("ts", Instant.now().toString());
            j.put("windowMs", intervalMs);
            j.put("requests", t);

            j.put("outCount.avg", safeDiv(sumOut, t));
            j.put("outCount.zeroRatio", safeRatio(z, t));

            j.put("budget_exhausted.ratio", safeRatio(be, t));
            j.put("brave.cooldown.ratio", safeRatio(cd, t));
            j.put("brave.disabled.ratio", safeRatio(dis, t));
            j.put("await.timeout.ratio", safeRatio(at, t));
            j.put("await.interrupted.ratio", safeRatio(ai, t));

            Map<String, Object> off = new LinkedHashMap<>();
            off.put("requests", ot);
            off.put("outCount.avg", safeDiv(osumOut, ot));
            off.put("outCount.zeroRatio", safeRatio(oz, ot));
            off.put("budget_exhausted.ratio", safeRatio(obe, ot));
            off.put("brave.cooldown.ratio", safeRatio(ocd, ot));
            off.put("brave.disabled.ratio", safeRatio(odis, ot));
            off.put("await.timeout.ratio", safeRatio(oat, ot));
            off.put("await.interrupted.ratio", safeRatio(oai, ot));
            j.put("officialOnly", off);

            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("await.min-live-budget-ms", awaitMinLiveBudgetMs);
            cfg.put("officialOnly.brave-full-join.max-wait-ms", officialOnlyBraveFullJoinMaxWaitMs);
            j.put("cfg", cfg);

            log.info(om.writeValueAsString(j));
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    private static double safeDiv(long sum, long n) {
        if (n <= 0L) {
            return 0.0;
        }
        return round2(sum / (double) n);
    }

    private static double safeRatio(long num, long den) {
        if (den <= 0L) {
            return 0.0;
        }
        return round2(num / (double) den);
    }

    private static double round2(double v) {
        // avoid noisy long decimals in logs
        return Math.round(v * 100.0) / 100.0;
    }
}
