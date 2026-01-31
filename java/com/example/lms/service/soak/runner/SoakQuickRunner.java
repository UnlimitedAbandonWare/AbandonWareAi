package com.example.lms.service.soak.runner;

import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.soak.SoakQuickReport;
import com.example.lms.service.soak.SoakRunResult;
import com.example.lms.service.soak.SoakTestService;
import com.example.lms.service.soak.metrics.SoakMetricRegistry;
import com.example.lms.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production-like soak quick runner:
 * <ul>
 * <li>Runs fixed query set (topic=naver-brave-fixed10 by default)
 * <li>Runs provider split (NAVER vs BRAVE) by overriding
 * GuardContext.webPrimary
 * <li>Writes artifacts/soak/quick_report.json with PASS/WARN/FAIL gate
 * <li>Supports CLI run-once (optional System.exit with exitCode) and scheduler
 * </ul>
 */
@Component
@EnableConfigurationProperties(SoakQuickRunnerProperties.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${soak.enabled:false} and ${soak.quick-runner.enabled:false}")
public class SoakQuickRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SoakQuickRunner.class);

    private final SoakTestService soakTestService;
    private final ObjectMapper objectMapper;
    private final SoakQuickRunnerProperties props;
    private final SoakMetricRegistry metricRegistry;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SoakQuickRunner(SoakTestService soakTestService, ObjectMapper objectMapper,
            SoakQuickRunnerProperties props, SoakMetricRegistry metricRegistry) {
        this.soakTestService = soakTestService;
        this.objectMapper = objectMapper;
        this.props = props;
        this.metricRegistry = metricRegistry;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (props.isCli()) {
            SoakQuickBundleReport bundle = runOnce("cli");
            int exit = (bundle == null) ? props.getGate().getFailExitCode() : bundle.summary.exitCode;
            if (props.isExitAfterRun()) {
                log.info("[SOAK] cli exit. exitCode={}", exit);
                System.exit(exit);
            }
            return;
        }

        if (props.isOnStartup()) {
            runOnce("startup");
        }
    }

    @Scheduled(cron = "${soak.quick-runner.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        if (!props.isScheduled()) {
            return;
        }
        try {
            runOnce("scheduled");
        } catch (Exception e) {
            log.error("[SOAK] scheduled quick_report failed", e);
        }
    }

    public SoakQuickBundleReport runOnce(String trigger) throws Exception {
        if (!running.compareAndSet(false, true)) {
            log.warn("[SOAK] quick_report skipped (already running). trigger={}", trigger);
            return null;
        }

        String sid = "soak-quick-" + UUID.randomUUID();
        String traceId = "soak.quick." + trigger + "." + UUID.randomUUID();

        try (AutoCloseable __ = TraceContext.attach(sid, traceId)) {
            SoakQuickBundleReport bundle = new SoakQuickBundleReport();
            bundle.generatedAt = Instant.now();
            bundle.topic = props.getTopic();
            bundle.k = props.getK();

            // gate config snapshot
            bundle.gate.warnEvidenceMin = props.getGate().getWarnEvidenceMin();
            bundle.gate.failEvidenceMin = props.getGate().getFailEvidenceMin();
            bundle.gate.warnHitMin = props.getGate().getWarnHitMin();
            bundle.gate.failHitMin = props.getGate().getFailHitMin();
            bundle.gate.warnExitCode = props.getGate().getWarnExitCode();
            bundle.gate.failExitCode = props.getGate().getFailExitCode();

            for (String providerRaw : props.getProviders()) {
                String provider = (providerRaw == null) ? "" : providerRaw.trim().toUpperCase(Locale.ROOT);
                if (provider.isEmpty())
                    continue;

                String providerSid = sid + "-" + provider.toLowerCase(Locale.ROOT);
                String providerTraceId = traceId + "." + provider.toLowerCase(Locale.ROOT);

                try (AutoCloseable __p = TraceContext.attach(providerSid, providerTraceId)) {
                    if (metricRegistry != null) {
                        metricRegistry.resetForSid(providerSid);
                    }

                    SoakQuickBundleReport.ProviderRun pr = new SoakQuickBundleReport.ProviderRun();
                    pr.provider = provider;

                    GuardContext prev = GuardContextHolder.get();
                    GuardContext ctx = GuardContext.defaultContext();
                    ctx.setPlanId("soak.quick.v3");
                    ctx.setHeaderMode("BRAVE".equals(provider) ? "brave" : "S1");
                    ctx.setMode("BRAVE".equals(provider) ? "BRAVE" : "SAFE");
                    ctx.setWebPrimary(provider);

                    try {
                        GuardContextHolder.set(ctx);
                        SoakQuickReport report = soakTestService.runQuick(props.getK(), props.getTopic());
                        pr.report = report;
                        pr.gate = evaluateGate(report, bundle.gate);
                    } catch (Exception e) {
                        pr.report = null;
                        pr.gate = new SoakQuickBundleReport.GateDecision();
                        pr.gate.status = "FAIL";
                        pr.gate.hitRate = 0.0;
                        pr.gate.evidenceRate = 0.0;
                        pr.gate.reasons.add("exception:" + e.getClass().getSimpleName());
                    } finally {
                        if (prev != null) {
                            GuardContextHolder.set(prev);
                        } else {
                            GuardContextHolder.clear();
                        }
                    }

                    if (metricRegistry != null) {
                        SoakMetricRegistry.Snapshot snap = metricRegistry.snapshot(providerSid);
                        SoakQuickBundleReport.ProviderMetrics pm = new SoakQuickBundleReport.ProviderMetrics();
                        pm.fpFilterLegacyBypassCount = snap.fpFilterLegacyBypassCount;
                        pm.webCalls = snap.webCalls;
                        pm.webCallsWithNaver = snap.webCallsWithNaver;
                        pm.webMergedTotal = snap.webMergedTotal;
                        pm.webMergedFromNaver = snap.webMergedFromNaver;
                        pm.naverCallInclusionRate = snap.naverCallInclusionRate;
                        pm.naverMergedShare = snap.naverMergedShare;
                        pr.metrics = pm;
                    }

                    bundle.providers.add(pr);
                }
            }

            finalizeSummary(bundle);

            Path out = Paths.get(props.getOutputPath());
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), bundle);

            String overall = bundle.summary.overallStatus;
            if ("FAIL".equalsIgnoreCase(overall)) {
                log.error("[SOAK] quick_report done. trigger={} overall={} exitCode={} out={}",
                        trigger, bundle.summary.overallStatus, bundle.summary.exitCode, out.toAbsolutePath());
            } else if ("WARN".equalsIgnoreCase(overall)) {
                log.warn("[SOAK] quick_report done. trigger={} overall={} exitCode={} out={}",
                        trigger, bundle.summary.overallStatus, bundle.summary.exitCode, out.toAbsolutePath());
            } else {
                log.info("[SOAK] quick_report done. trigger={} overall={} exitCode={} out={}",
                        trigger, bundle.summary.overallStatus, bundle.summary.exitCode, out.toAbsolutePath());
            }

            return bundle;
        } finally {
            running.set(false);
        }
    }

    private SoakQuickBundleReport.GateDecision evaluateGate(SoakQuickReport report,
            SoakQuickBundleReport.GateConfig cfg) {
        SoakQuickBundleReport.GateDecision gd = new SoakQuickBundleReport.GateDecision();

        if (report == null || report.items == null || report.items.isEmpty()) {
            gd.status = "FAIL";
            gd.hitRate = 0.0;
            gd.evidenceRate = 0.0;
            gd.reasons.add("missing_report_or_items");
            return gd;
        }

        int total = report.items.size();
        int succ = 0;
        int ev = 0;
        for (SoakQuickReport.Item i : report.items) {
            if (i == null)
                continue;
            if (i.isSuccess())
                succ++;
            if (i.isHasEvidence())
                ev++;
        }

        double hit = total == 0 ? 0.0 : succ * 1.0 / total;
        double evidence = total == 0 ? 0.0 : ev * 1.0 / total;

        gd.hitRate = hit;
        gd.evidenceRate = evidence;

        boolean fail = false;
        boolean warn = false;

        if (hit < cfg.failHitMin) {
            fail = true;
            gd.reasons.add("hitRate<failHitMin(" + cfg.failHitMin + ")");
        } else if (hit < cfg.warnHitMin) {
            warn = true;
            gd.reasons.add("hitRate<warnHitMin(" + cfg.warnHitMin + ")");
        }

        if (evidence < cfg.failEvidenceMin) {
            fail = true;
            gd.reasons.add("evidenceRate<failEvidenceMin(" + cfg.failEvidenceMin + ")");
        } else if (evidence < cfg.warnEvidenceMin) {
            warn = true;
            gd.reasons.add("evidenceRate<warnEvidenceMin(" + cfg.warnEvidenceMin + ")");
        }

        gd.status = fail ? "FAIL" : (warn ? "WARN" : "PASS");
        return gd;
    }

    private void finalizeSummary(SoakQuickBundleReport bundle) {
        boolean anyFail = false;
        boolean anyWarn = false;

        long totalBypass = 0L;
        long totalWebCalls = 0L;
        long totalWebCallsWithNaver = 0L;
        long totalMergedTotal = 0L;
        long totalMergedFromNaver = 0L;

        for (SoakQuickBundleReport.ProviderRun pr : bundle.providers) {
            if (pr == null)
                continue;

            if (pr.gate != null && pr.gate.status != null) {
                String s = pr.gate.status.toUpperCase(Locale.ROOT);
                if ("FAIL".equals(s)) {
                    anyFail = true;
                    bundle.summary.failedProviders.add(pr.provider);
                } else if ("WARN".equals(s)) {
                    anyWarn = true;
                    bundle.summary.warnedProviders.add(pr.provider);
                }
            }

            if (pr.metrics != null) {
                totalBypass += pr.metrics.fpFilterLegacyBypassCount;
                totalWebCalls += pr.metrics.webCalls;
                totalWebCallsWithNaver += pr.metrics.webCallsWithNaver;
                totalMergedTotal += pr.metrics.webMergedTotal;
                totalMergedFromNaver += pr.metrics.webMergedFromNaver;
            }
        }

        // metrics (aggregated)
        bundle.summary.totalFpFilterLegacyBypassCount = totalBypass;
        bundle.summary.overallNaverCallInclusionRate = (totalWebCalls == 0L) ? 0.0
                : (totalWebCallsWithNaver * 1.0 / totalWebCalls);
        bundle.summary.overallNaverMergedShare = (totalMergedTotal == 0L) ? 0.0
                : (totalMergedFromNaver * 1.0 / totalMergedTotal);

        if (anyFail) {
            bundle.summary.overallStatus = "FAIL";
            bundle.summary.exitCode = bundle.gate.failExitCode;
            return;
        }
        if (anyWarn) {
            bundle.summary.overallStatus = "WARN";
            bundle.summary.exitCode = bundle.gate.warnExitCode;
            return;
        }
        bundle.summary.overallStatus = "PASS";
        bundle.summary.exitCode = 0;
    }
}
