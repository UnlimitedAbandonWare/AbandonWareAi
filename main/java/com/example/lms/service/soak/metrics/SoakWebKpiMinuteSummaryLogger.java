package com.example.lms.service.soak.metrics;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAccumulator;
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
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;

    public SoakWebKpiMinuteSummaryLogger(ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this.debugEventStoreProvider = debugEventStoreProvider;
    }

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
    private final LongAdder poolSafeEmpty = new LongAdder();
    private final LongAdder rescueMergeUsed = new LongAdder();
    private final LongAdder vectorFallbackUsed = new LongAdder();
    private final LongAdder cacheOnlyMergedCount = new LongAdder();
    private final LongAccumulator tracePoolSizeMax = new LongAccumulator(Long::max, 0L);
    private final LongAdder starvationFallbackTriggered = new LongAdder();

    // --- officialOnly sub-window counters ---
    private final LongAdder officialTotal = new LongAdder();
    private final LongAdder officialSumOutCount = new LongAdder();
    private final LongAdder officialOutZero = new LongAdder();
    private final LongAdder officialBudgetExhausted = new LongAdder();
    private final LongAdder officialBraveCooldown = new LongAdder();
    private final LongAdder officialBraveDisabled = new LongAdder();
    private final LongAdder officialAwaitTimeout = new LongAdder();
    private final LongAdder officialAwaitInterrupted = new LongAdder();
    private final LongAdder officialPoolSafeEmpty = new LongAdder();
    private final LongAdder officialRescueMergeUsed = new LongAdder();
    private final LongAdder officialVectorFallbackUsed = new LongAdder();
    private final LongAdder officialCacheOnlyMergedCount = new LongAdder();
    private final LongAccumulator officialTracePoolSizeMax = new LongAccumulator(Long::max, 0L);
    private final LongAdder officialStarvationFallbackTriggered = new LongAdder();

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
        record(outCount,
                officialOnly,
                budgetExhaustedFlag,
                braveCooldownFlag,
                braveDisabledFlag,
                awaitTimeoutFlag,
                awaitInterruptedFlag,
                false,
                false,
                false,
                0L,
                0L,
                false);
    }

    /**
     * Record a single request outcome with ladder-rescue diagnostics.
     */
    public void record(int outCount,
            boolean officialOnly,
            boolean budgetExhaustedFlag,
            boolean braveCooldownFlag,
            boolean braveDisabledFlag,
            boolean awaitTimeoutFlag,
            boolean awaitInterruptedFlag,
            boolean poolSafeEmptyFlag,
            boolean rescueMergeUsedFlag,
            boolean vectorFallbackUsedFlag,
            long cacheOnlyMergedCountValue,
            long tracePoolSizeValue,
            boolean starvationFallbackTriggeredFlag) {

        try {
            int oc = Math.max(0, outCount);
            long cacheMerged = Math.max(0L, cacheOnlyMergedCountValue);
            long tracePoolSize = Math.max(0L, tracePoolSizeValue);
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
            if (poolSafeEmptyFlag) {
                poolSafeEmpty.increment();
            }
            if (rescueMergeUsedFlag) {
                rescueMergeUsed.increment();
            }
            if (vectorFallbackUsedFlag) {
                vectorFallbackUsed.increment();
            }
            if (cacheMerged > 0L) {
                cacheOnlyMergedCount.add(cacheMerged);
            }
            if (tracePoolSize > 0L) {
                tracePoolSizeMax.accumulate(tracePoolSize);
            }
            if (starvationFallbackTriggeredFlag) {
                starvationFallbackTriggered.increment();
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
                if (poolSafeEmptyFlag) {
                    officialPoolSafeEmpty.increment();
                }
                if (rescueMergeUsedFlag) {
                    officialRescueMergeUsed.increment();
                }
                if (vectorFallbackUsedFlag) {
                    officialVectorFallbackUsed.increment();
                }
                if (cacheMerged > 0L) {
                    officialCacheOnlyMergedCount.add(cacheMerged);
                }
                if (tracePoolSize > 0L) {
                    officialTracePoolSizeMax.accumulate(tracePoolSize);
                }
                if (starvationFallbackTriggeredFlag) {
                    officialStarvationFallbackTriggered.increment();
                }
            }
        } catch (RuntimeException ignore) {
            TraceStore.put("soak.webKpi.suppressed.record", true);
            TraceStore.put("soak.webKpi.suppressed.record.errorType", errorType(ignore));
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
            long pse = poolSafeEmpty.sumThenReset();
            long rmu = rescueMergeUsed.sumThenReset();
            long vfu = vectorFallbackUsed.sumThenReset();
            long cacheMergedSum = cacheOnlyMergedCount.sumThenReset();
            long tracePoolMax = tracePoolSizeMax.getThenReset();
            long sft = starvationFallbackTriggered.sumThenReset();

            long ot = officialTotal.sumThenReset();
            long osumOut = officialSumOutCount.sumThenReset();
            long oz = officialOutZero.sumThenReset();
            long obe = officialBudgetExhausted.sumThenReset();
            long ocd = officialBraveCooldown.sumThenReset();
            long odis = officialBraveDisabled.sumThenReset();
            long oat = officialAwaitTimeout.sumThenReset();
            long oai = officialAwaitInterrupted.sumThenReset();
            long opse = officialPoolSafeEmpty.sumThenReset();
            long ormu = officialRescueMergeUsed.sumThenReset();
            long ovfu = officialVectorFallbackUsed.sumThenReset();
            long ocacheMergedSum = officialCacheOnlyMergedCount.sumThenReset();
            long otracePoolMax = officialTracePoolSizeMax.getThenReset();
            long osft = officialStarvationFallbackTriggered.sumThenReset();

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
            j.put("poolSafeEmpty.ratio", safeRatio(pse, t));
            j.put("rescueMerge.used.ratio", safeRatio(rmu, t));
            j.put("vectorFallback.used.ratio", safeRatio(vfu, t));
            j.put("cacheOnly.merged.count.sum", cacheMergedSum);
            j.put("tracePool.size.max", tracePoolMax);
            j.put("starvationFallback.trigger.ratio", safeRatio(sft, t));

            Map<String, Object> off = new LinkedHashMap<>();
            off.put("requests", ot);
            off.put("outCount.avg", safeDiv(osumOut, ot));
            off.put("outCount.zeroRatio", safeRatio(oz, ot));
            off.put("budget_exhausted.ratio", safeRatio(obe, ot));
            off.put("brave.cooldown.ratio", safeRatio(ocd, ot));
            off.put("brave.disabled.ratio", safeRatio(odis, ot));
            off.put("await.timeout.ratio", safeRatio(oat, ot));
            off.put("await.interrupted.ratio", safeRatio(oai, ot));
            off.put("poolSafeEmpty.ratio", safeRatio(opse, ot));
            off.put("rescueMerge.used.ratio", safeRatio(ormu, ot));
            off.put("vectorFallback.used.ratio", safeRatio(ovfu, ot));
            off.put("cacheOnly.merged.count.sum", ocacheMergedSum);
            off.put("tracePool.size.max", otracePoolMax);
            off.put("starvationFallback.trigger.ratio", safeRatio(osft, ot));
            j.put("officialOnly", off);

            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("await.min-live-budget-ms", awaitMinLiveBudgetMs);
            cfg.put("officialOnly.brave-full-join.max-wait-ms", officialOnlyBraveFullJoinMaxWaitMs);
            j.put("cfg", cfg);

            emitDebugEvent(t, z, be, cd, dis, at, ai, pse, rmu, vfu, cacheMergedSum, tracePoolMax, sft,
                    ot, oz, obe, ocd, odis, oat, oai, opse, ormu, ovfu, ocacheMergedSum, otracePoolMax, osft);
            log.info(om.writeValueAsString(j));
        } catch (Throwable ignore) {
            TraceStore.put("soak.webKpi.suppressed.emitSummary", true);
            TraceStore.put("soak.webKpi.suppressed.emitSummary.errorType", errorType(ignore));
        }
    }

    private void emitDebugEvent(long total,
                                long outZero,
                                long budgetExhausted,
                                long braveCooldown,
                                long braveDisabled,
                                long awaitTimeout,
                                long awaitInterrupted,
                                long poolSafeEmpty,
                                long rescueMergeUsed,
                                long vectorFallbackUsed,
                                long cacheOnlyMergedCount,
                                long tracePoolSizeMax,
                                long starvationFallbackTriggered,
                                long officialTotal,
                                long officialOutZero,
                                long officialBudgetExhausted,
                                long officialBraveCooldown,
                                long officialBraveDisabled,
                                long officialAwaitTimeout,
                                long officialAwaitInterrupted,
                                long officialPoolSafeEmpty,
                                long officialRescueMergeUsed,
                                long officialVectorFallbackUsed,
                                long officialCacheOnlyMergedCount,
                                long officialTracePoolSizeMax,
                                long officialStarvationFallbackTriggered) {
        boolean anomaly = outZero > 0L
                || budgetExhausted > 0L
                || braveCooldown > 0L
                || braveDisabled > 0L
                || awaitTimeout > 0L
                || awaitInterrupted > 0L
                || poolSafeEmpty > 0L
                || rescueMergeUsed > 0L
                || vectorFallbackUsed > 0L
                || cacheOnlyMergedCount > 0L
                || tracePoolSizeMax > 0L
                || starvationFallbackTriggered > 0L
                || officialOutZero > 0L
                || officialBudgetExhausted > 0L
                || officialBraveCooldown > 0L
                || officialBraveDisabled > 0L
                || officialAwaitTimeout > 0L
                || officialAwaitInterrupted > 0L
                || officialPoolSafeEmpty > 0L
                || officialRescueMergeUsed > 0L
                || officialVectorFallbackUsed > 0L
                || officialCacheOnlyMergedCount > 0L
                || officialTracePoolSizeMax > 0L
                || officialStarvationFallbackTriggered > 0L;
        if (!anomaly || debugEventStoreProvider == null) {
            return;
        }
        DebugEventStore store = debugEventStoreProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        boolean zeroResult = outZero > 0L || officialOutZero > 0L;
        boolean interrupted = awaitInterrupted > 0L || officialAwaitInterrupted > 0L;
        data.put("layer", "web.search");
        data.put("failureClass", zeroResult
                ? "zero-result-after-filter"
                : interrupted ? "cancelled" : "soak-web-kpi-anomaly");
        data.put("reason", zeroResult ? "out-count-zero" : interrupted ? "await-interrupted" : "soak-kpi-anomaly");
        data.put("requests", total);
        data.put("outCount.zeroRatio", safeRatio(outZero, total));
        data.put("officialOnly.requests", officialTotal);
        data.put("officialOnly.outCount.zeroRatio", safeRatio(officialOutZero, officialTotal));
        data.put("budget_exhausted.ratio", safeRatio(budgetExhausted, total));
        data.put("brave.cooldown.ratio", safeRatio(braveCooldown, total));
        data.put("brave.disabled.ratio", safeRatio(braveDisabled, total));
        data.put("await.timeout.ratio", safeRatio(awaitTimeout, total));
        data.put("await.interrupted.ratio", safeRatio(awaitInterrupted, total));
        data.put("poolSafeEmpty.ratio", safeRatio(poolSafeEmpty, total));
        data.put("rescueMerge.used.ratio", safeRatio(rescueMergeUsed, total));
        data.put("vectorFallback.used.ratio", safeRatio(vectorFallbackUsed, total));
        data.put("cacheOnly.merged.count.sum", cacheOnlyMergedCount);
        data.put("tracePool.size.max", tracePoolSizeMax);
        data.put("starvationFallback.trigger.ratio", safeRatio(starvationFallbackTriggered, total));
        data.put("officialOnly.poolSafeEmpty.ratio", safeRatio(officialPoolSafeEmpty, officialTotal));
        data.put("officialOnly.rescueMerge.used.ratio", safeRatio(officialRescueMergeUsed, officialTotal));
        data.put("officialOnly.vectorFallback.used.ratio", safeRatio(officialVectorFallbackUsed, officialTotal));
        data.put("officialOnly.cacheOnly.merged.count.sum", officialCacheOnlyMergedCount);
        data.put("officialOnly.tracePool.size.max", officialTracePoolSizeMax);
        data.put("officialOnly.starvationFallback.trigger.ratio",
                safeRatio(officialStarvationFallbackTriggered, officialTotal));
        store.emit(
                DebugProbeType.WEB_SEARCH,
                DebugEventLevel.WARN,
                "soak-web-kpi-minute-summary",
                "[AWX][search][zero-results] soak web KPI minute summary",
                "SoakWebKpiMinuteSummaryLogger.emitMinuteSummary",
                data,
                null);
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

    private static String errorType(Throwable failure) {
        return failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
