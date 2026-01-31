package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.*;

/**
 * Injects a compact TraceStore summary into degraded user-facing outputs.
 *
 * <p>
 * Motivation:
 * <ul>
 * <li>When the system degrades (e.g. DEGRADE_EVIDENCE_LIST or LLM-fast-bail
 * evidence answer),
 * the UX can look like "URLs only" and the rationale (검색·게이트·라우팅) is
 * hidden.</li>
 * <li>We expose a <b>safe, clipped</b> diagnostic header so users/operators can
 * see
 * why a degrade happened and which rescue ladder fired
 * (minCitationsRescue/citeableTopUp,
 * starvationFallback, cacheOnly, cancelShield).</li>
 * </ul>
 *
 * <p>
 * Safety:
 * </p>
 * <ul>
 * <li>Only selected keys (orch/aux/guard/web-failsoft/cancelShield KPI) are
 * shown.</li>
 * <li>Values are redacted via {@link SafeRedactor} and clipped by
 * maxLines/maxChars.</li>
 * </ul>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class EvidenceListTraceInjectionAspect {

    private static final String MARKER = "<!-- NOVA_TRACE_INJECTED -->";

    private final Environment env;

    public EvidenceListTraceInjectionAspect(Environment env) {
        this.env = env;
    }

    @Around("execution(* com.example.lms.service.guard.EvidenceAwareGuard.degradeToEvidenceList(..))")
    public Object aroundDegradeToEvidenceList(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();
        if (!(ret instanceof String base)) {
            return ret;
        }
        if (!enabled() || base.contains(MARKER)) {
            return ret;
        }

        String diag = buildDiagnosticsBlock();
        if (diag == null || diag.isBlank()) {
            return ret;
        }
        return inject(base, diag);
    }

    /**
     * When the main LLM path times out (LLM_FAST_BAIL_TIMEOUT) the system may fall
     * back to a
     * deterministic evidence-based answer via {@code EvidenceAnswerComposer}. That
     * path does not
     * go through {@code EvidenceAwareGuard.degradeToEvidenceList(..)}, so we inject
     * the same
     * diagnostics block here as well.
     */
    @Around("execution(public String com.example.lms.service.rag.EvidenceAnswerComposer.compose(..))")
    public Object aroundEvidenceAnswerComposer(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();
        if (!(ret instanceof String base)) {
            return ret;
        }
        if (!enabled() || base.contains(MARKER)) {
            return ret;
        }

        String diag = buildDiagnosticsBlock();
        if (diag == null || diag.isBlank()) {
            return ret;
        }
        return inject(base, diag);
    }

    /**
     * EvidenceAwareGuard.ensureCoverage() 내부에서 degradeToEvidenceList()가
     * self-invocation으로 호출되어
     * degradeToEvidenceList() pointcut이 적용되지 않는 경우가 많습니다.
     *
     * <p>
     * 따라서 ensureCoverage() 반환 시점에서 결과 문자열을 post-process 하여 진단 헤더를 주입합니다.
     * </p>
     */
    @Around("execution(public com.example.lms.service.guard.EvidenceAwareGuard$Result com.example.lms.service.guard.EvidenceAwareGuard.ensureCoverage(..))")
    public Object aroundEnsureCoverage(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();
        if (!(ret instanceof com.example.lms.service.guard.EvidenceAwareGuard.Result r)) {
            return ret;
        }
        if (!enabled()) {
            return ret;
        }

        String base = r.answer;
        if (base == null || base.isBlank() || base.contains(MARKER)) {
            return ret;
        }

        // Only inject for evidence-list style degradations (avoid polluting normal
        // answers).
        if (!shouldInjectForEnsureCoverage(base)) {
            return ret;
        }

        String diag = buildDiagnosticsBlock();
        if (diag == null || diag.isBlank()) {
            return ret;
        }

        String merged = inject(base, diag);
        return new com.example.lms.service.guard.EvidenceAwareGuard.Result(merged, r.escalated());
    }

    private boolean enabled() {
        // Default: clean output. Enable explicitly when diagnosing in non-user contexts.
        return env.getProperty("nova.orch.evidence-list.trace-injection.enabled", Boolean.class, false);
    }

    private boolean shouldInjectForEnsureCoverage(String base) {
        try {
            Map<String, Object> ctx = TraceStore.context();
            if (ctx != null) {
                String guardAction = safeInline(firstNonNull(ctx.get("guard.final.action"), ctx.get("guard.action")),
                        128);
                if (containsIgnoreCase(guardAction, "DEGRADE") && containsIgnoreCase(guardAction, "EVIDENCE")) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }

        // Fallback heuristic (when trace keys are missing): look for the evidence-list
        // section headers.
        return containsIgnoreCase(base, "참고 자료")
                || containsIgnoreCase(base, "근거")
                || containsIgnoreCase(base, "검색 결과")
                || containsIgnoreCase(base, "evidence");
    }

    private String inject(String base, String diag) {
        // Default to APPEND (prepend=false).
        // When prepend=true, the marker lands at the start of the answer;
        // if any downstream redaction logic cuts at the first marker, that can
        // accidentally erase the entire user-visible body.
        boolean prepend = getBool(
                "nova.orch.evidence-list.trace-injection.prepend",
                "nova.orch.evidence-list.trace-injection.prepend",
                false);

        // Observability breadcrumb
        try {
            TraceStore.put("orch.evidenceList.traceInjected", true);
            TraceStore.put("orch.evidenceList.traceInjected.prepend", prepend);
        } catch (Throwable ignore) {
        }

        if (prepend) {
            return MARKER + "\n" + diag + "\n\n" + base;
        }
        return base + "\n\n" + MARKER + "\n" + diag;
    }

    private boolean getBool(String keyA, String keyB, boolean defaultValue) {
        Boolean v = null;
        try {
            v = env.getProperty(keyA, Boolean.class);
        } catch (Exception ignore) {
            // ignore
        }
        if (v == null) {
            try {
                v = env.getProperty(keyB, Boolean.class);
            } catch (Exception ignore) {
                // ignore
            }
        }
        return v != null ? v : defaultValue;
    }

    private int getInt(String keyA, String keyB, int defaultValue) {
        Integer v = null;
        try {
            v = env.getProperty(keyA, Integer.class);
        } catch (Exception ignore) {
            // ignore
        }
        if (v == null) {
            try {
                v = env.getProperty(keyB, Integer.class);
            } catch (Exception ignore) {
                // ignore
            }
        }
        return v != null ? v : defaultValue;
    }

    private static String clipAndCloseDetails(String rendered, int maxLines, int maxChars) {
        if (rendered == null || rendered.isBlank()) {
            return "";
        }
        // Normalize newlines
        String s = rendered.replace("\r\n", "\n").replace("\r", "\n");

        boolean clipped = false;
        if (maxChars > 0 && s.length() > maxChars) {
            s = s.substring(0, maxChars);
            clipped = true;
        }

        if (maxLines > 0) {
            String[] lines = s.split("\n", -1);
            if (lines.length > maxLines) {
                s = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, maxLines));
                clipped = true;
            }
        }

        if (clipped) {
            s = s + "\n… (clipped)\n";
        }

        // Ensure fenced blocks are not left open after clipping.
        int fenceCount = countOccurrences(s, "```");
        if (fenceCount % 2 == 1) {
            s = s + "\n```\n";
        }

        // Ensure <details> is closed even when clipping removes the tail.
        if (s.contains("<details") && !s.contains("</details>")) {
            s = s + "\n</details>\n";
        }

        return s;
    }

    private static int countOccurrences(String s, String needle) {
        if (s == null || needle == null || needle.isEmpty())
            return 0;
        int count = 0;
        int idx = 0;
        while (true) {
            idx = s.indexOf(needle, idx);
            if (idx < 0)
                break;
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String buildDiagnosticsBlock() {
        Map<String, Object> ctx;
        try {
            ctx = TraceStore.context();
        } catch (Throwable t) {
            return "";
        }
        if (ctx == null || ctx.isEmpty()) {
            return "";
        }

        boolean dbg = truthy(ctx.get("dbg.search.enabled")) || truthy(ctx.get("dbg.search.boost.active"));
        // NOTE: output diagnostics as pure Markdown (no <details>) so clients can't hide it.

        boolean includeSelectedKeys = getBool("nova.orch.evidence-list.trace-injection.include-selected-keys",
                "nova.orch.evidence-list.trace-injection.includeSelectedKeys", false);
        boolean redact = getBool("nova.orch.evidence-list.trace-injection.redact",
                "nova.orch.evidence-list.trace-injection.redaction", true);
        int maxLines = getInt("nova.orch.evidence-list.trace-injection.max-lines",
                "nova.orch.evidence-list.trace-injection.maxLines", 70);
        int maxChars = getInt("nova.orch.evidence-list.trace-injection.max-chars",
                "nova.orch.evidence-list.trace-injection.maxChars", 3600);

        String sid = firstNonBlank(getString(ctx, "sessionId"), getString(ctx, "sid"));
        String rid = firstNonBlank(
                getString(ctx, "rid"),
                getString(ctx, "x-request-id"),
                getString(ctx, "requestId"),
                getString(ctx, "trace.id"),
                getString(ctx, "trace"),
                getString(ctx, "traceId"));

        // ---- Plan/Mode ----
        String planId = firstNonBlank(getString(ctx, "plan.id"), getString(ctx, "orch.plan"), getString(ctx, "plan"));
        String planOrder = safeInline(ctx.get("plan.order"), 240);
        String orchMode = firstNonBlank(getString(ctx, "orch.mode"), getString(ctx, "mode"));
        String orchReason = safeInline(ctx.get("orch.reason"), 240);

        boolean strike = truthy(ctx.get("orch.strike"));
        boolean compression = truthy(ctx.get("orch.compression"));
        boolean bypass = truthy(ctx.get("orch.bypass"));

        // ---- WebFailSoft KPIs ----
        Object outCountObj = firstNonNull(
                ctx.get("outCount"),
                ctx.get("web.failsoft.stageCountsSelectedFromOut.outCount"),
                ctx.get("web.failsoft.stageCountsSelectedFromOut.last.outCount"));
        String outCount = (outCountObj == null) ? "" : safeInline(outCountObj, 64);

        Object stageCountsObj = firstNonNull(
                ctx.get("web.failsoft.stageCountsSelectedFromOut"),
                ctx.get("stageCountsSelectedFromOut"),
                ctx.get("web.failsoft.stageCountsSelectedFromOut.last"));
        String stageCounts = renderStageCounts(stageCountsObj);

        String starvationFallback = safeInline(firstNonNull(
                ctx.get("web.failsoft.starvationFallback"),
                ctx.get("starvationFallback")), 240);

        String starvationTrigger = safeInline(firstNonNull(
                ctx.get("web.failsoft.starvationFallback.trigger"),
                ctx.get("starvationFallback.trigger")), 80);

        String poolUsed = safeInline(firstNonNull(
                ctx.get("web.failsoft.starvationFallback.poolUsed"),
                ctx.get("starvationFallback.poolUsed")), 80);

        boolean starvationUsed = truthy(firstNonNull(
                ctx.get("web.failsoft.starvationFallback.used"),
                ctx.get("starvationFallback.used"),
                ctx.get("web.failsoft.starvationFallback")));

        String poolSafeEmpty = safeInline(firstNonNull(
                ctx.get("web.failsoft.starvationFallback.poolSafeEmpty"),
                ctx.get("starvationFallback.poolSafeEmpty"),
                ctx.get("poolSafeEmpty")), 64);

        String cacheMerged = safeInline(firstNonNull(
                ctx.get("cacheOnly.merged.count"),
                ctx.get("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count")), 64);

        String executedQuery = safeInline(firstNonNull(
                ctx.get("web.failsoft.executedQuery"),
                ctx.get("web.executedQuery")), 240);
        String extraQuery = safeInline(firstNonNull(
                ctx.get("web.failsoft.extraQuery"),
                ctx.get("web.failsoft.minCitationsRescue.rescueQuery")), 240);

        // ---- Citations gate KPIs ----
        String officialOnly = safeInline(firstNonNull(
                ctx.get("plan.officialOnly"),
                ctx.get("web.failsoft.officialOnly"),
                ctx.get("officialOnly")), 24);

        String minCitations = safeInline(firstNonNull(
                ctx.get("plan.minCitations"),
                ctx.get("web.failsoft.minCitations"),
                ctx.get("minCitations")), 24);

        String minCitationsNeeded = safeInline(firstNonNull(
                ctx.get("web.failsoft.minCitationsNeeded"),
                ctx.get("minCitationsNeeded")), 24);

        String citeableCount = safeInline(firstNonNull(
                ctx.get("web.failsoft.citeableCount"),
                ctx.get("citeableCount")), 24);

        String citeableTopUpUsed = safeInline(firstNonNull(
                ctx.get("web.failsoft.citeableTopUp.used")), 24);
        String citeableTopUpAdded = safeInline(firstNonNull(
                ctx.get("web.failsoft.citeableTopUp.added")), 24);
        String citeableTopUpTailDrop = safeInline(firstNonNull(
                ctx.get("web.failsoft.citeableTopUp.tailDrop.count")), 24);

        String minCitationsRescueAttempted = safeInline(firstNonNull(
                ctx.get("web.failsoft.minCitationsRescue.attempted")), 24);
        String minCitationsRescueInserted = safeInline(firstNonNull(
                ctx.get("web.failsoft.minCitationsRescue.insertedCount")), 24);
        String minCitationsRescueSatisfied = safeInline(firstNonNull(
                ctx.get("web.failsoft.minCitationsRescue.satisfied")), 24);

        // ---- CancelShield KPIs ----
        String cancelTrue = safeInline(firstNonNull(ctx.get("ops.cancelShield.cancelTrue.count")), 24);
        String downgraded = safeInline(firstNonNull(ctx.get("ops.cancelShield.downgraded.count")), 24);
        String softCancelled = safeInline(firstNonNull(ctx.get("ops.cancelShield.softCancelled.count")), 24);

        // ---- Providers / Await / Interrupt hygiene ----
        String webHardDown = safeInline(firstNonNull(ctx.get("web.hardDown"), ctx.get("webHardDown")), 24);

        String braveSkippedReason = safeInline(firstNonNull(ctx.get("web.brave.skipped.reason")), 180);
        String braveSkippedExtraMs = safeInline(firstNonNull(ctx.get("web.brave.skipped.extraMs")), 24);
        String braveSkippedCount = safeInline(firstNonNull(ctx.get("web.brave.skipped.count")), 24);

        String naverSkippedReason = safeInline(firstNonNull(ctx.get("web.naver.skipped.reason")), 180);
        String naverSkippedExtraMs = safeInline(firstNonNull(ctx.get("web.naver.skipped.extraMs")), 24);
        String naverSkippedCount = safeInline(firstNonNull(ctx.get("web.naver.skipped.count")), 24);

        String awaitSkippedCount = safeInline(firstNonNull(ctx.get("web.await.skipped.count")), 24);
        String awaitRootEngine = safeInline(firstNonNull(ctx.get("web.await.root.engine")), 40);
        String awaitRootCause = safeInline(firstNonNull(ctx.get("web.await.root.cause")), 180);
        String awaitRootWaitedMs = safeInline(firstNonNull(ctx.get("web.await.root.waitedMs")), 24);


        // Web partial-down (provider disabled/cooldown) signals
        boolean orchWebPartialDownAnyDown = truthy(firstNonNull(
                ctx.get("orch.webPartialDown.anyDown"),
                ctx.get("orch.webPartialDown")));
        String awaitBraveDisabledReason = safeInline(firstNonNull(ctx.get("web.await.brave.disabledReason")), 180);
        boolean naverStrictDomainRequiredDemoted = truthy(firstNonNull(ctx.get("web.naver.strictDomainRequiredDemoted")));
        String naverStrictDomainRequiredDemotedReason = safeInline(firstNonNull(
                ctx.get("web.naver.strictDomainRequiredDemoted.reason")), 80);

        // CancelShield bulk policies
        String invokeAllTimedOut = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAll.timeout.timedOut")), 24);
        String invokeAllCancelAttempted = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAll.timeout.cancelAttempted")), 24);
        String invokeAllCancelSucceeded = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAll.timeout.cancelSucceeded")), 24);
        String invokeAllMaxInflight = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAll.timeout.maxInflight")), 24);
        String invokeAllSubmitted = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAll.timeout.submitted")), 24);
        String invokeAllCompleted = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAll.timeout.completed")), 24);

        String invokeAnyTimedOut = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAny.timedOut")), 24);
        String invokeAnySuccess = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAny.success")), 24);
        String invokeAnySubmitted = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAny.submitted")), 24);
        String invokeAnyCancelAttempted = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAny.cancelAttempted")), 24);
        String invokeAnyCancelSucceeded = safeInline(firstNonNull(ctx.get("ops.cancelShield.invokeAny.cancelSucceeded")), 24);

        // Interrupt hygiene (external providers)
        String webIhEntryCleared = safeInline(firstNonNull(ctx.get("web.interruptHygiene.cleared.entry.count")), 24);
        String webIhExitCleared = safeInline(firstNonNull(ctx.get("web.interruptHygiene.cleared.exit.count")), 24);
        String naverIhEntryCleared = safeInline(firstNonNull(ctx.get("naver.interruptHygiene.cleared.entry.count")), 24);
        String naverIhExitCleared = safeInline(firstNonNull(ctx.get("naver.interruptHygiene.cleared.exit.count")), 24);
        String naverIhSwallowed = safeInline(firstNonNull(ctx.get("naver.interruptHygiene.swallowed.count")), 24);

        // Orch auto report / timeline
        String autoReportText = safeBlock(firstNonNull(ctx.get("orch.autoReport.text")), 2000);

        // ---- Aux ----
        String auxKeywordSel = safeInline(firstNonNull(
                ctx.get("aux.keywordSelection"),
                ctx.get("keywordSelection")), 240);
        String auxKeywordSelReason = safeInline(firstNonNull(
                ctx.get("aux.keywordSelection.degraded.reason"),
                ctx.get("aux.keywordSelection.reason")), 240);
        String auxQtx = safeInline(firstNonNull(
                ctx.get("aux.queryTransformer"),
                ctx.get("qtx"),
                ctx.get("queryTransformer")), 240);

        String auxQtxDegradedReason = safeInline(firstNonNull(
                ctx.get("aux.queryTransformer.degraded.reason"),
                ctx.get("aux.queryTransformer.degradedReason")), 240);
        String auxQtxDegradedCount = safeInline(firstNonNull(
                ctx.get("aux.queryTransformer.degraded.count"),
                ctx.get("aux.queryTransformer.degradedCount")), 24);
        // ---- Guard ----
        String guardAction = safeInline(firstNonNull(
                ctx.get("guard.final.action"),
                ctx.get("guard.action")), 128);
        String guardNote = safeInline(firstNonNull(
                ctx.get("guard.final.note"),
                ctx.get("guard.note")), 240);

        List<String> fixHints = buildFixHints(ctx);

        StringBuilder sb = new StringBuilder(1536);

        // Visible one-line summary ("thinking setup")
        sb.append("> 🔎 **라우팅/진단 요약**: ");
        if (planId != null && !planId.isBlank()) {
            sb.append("plan=`").append(escapeMd(safeInline(planId, 80))).append("` ");
        }
        if (guardAction != null && !guardAction.isBlank() && !"null".equalsIgnoreCase(guardAction)) {
            sb.append("guard=`").append(escapeMd(safeInline(guardAction, 80))).append("` ");
        }
        if (!outCount.isBlank() && !"null".equalsIgnoreCase(outCount)) {
            sb.append("web(outCount=").append(escapeMd(outCount)).append(") ");
        }
        if (starvationUsed) {
            String t = (starvationTrigger == null || starvationTrigger.isBlank()
                    || "null".equalsIgnoreCase(starvationTrigger))
                            ? ""
                            : (" trigger=" + starvationTrigger);
            String p = (poolUsed == null || poolUsed.isBlank() || "null".equalsIgnoreCase(poolUsed))
                    ? ""
                    : (" pool=" + poolUsed);
            sb.append("starvationFallback").append(escapeMd(t)).append(escapeMd(p)).append(" ");
        }
        if (!minCitationsNeeded.isBlank() && !"null".equalsIgnoreCase(minCitationsNeeded)) {
            String cc = (citeableCount == null || citeableCount.isBlank() || "null".equalsIgnoreCase(citeableCount))
                    ? "?"
                    : citeableCount;
            sb.append("citations(").append(cc).append("/").append(minCitationsNeeded).append(") ");
        }
        if (!downgraded.isBlank() && !"null".equalsIgnoreCase(downgraded)) {
            sb.append("cancelShield(downgraded=").append(escapeMd(downgraded)).append(") ");
        }
        sb.append("\n\n");

        sb.append("---\n");
        sb.append("**🔎 진단 상세 (Plan/Mode · Web/Citation · Providers · Aux/Guard · Cancel/Interrupt)**\n\n");

        sb.append("**Correlation**\n");
        if (sid != null && !sid.isBlank()) {
            sb.append("- sid: `").append(escapeMd(safeInline(sid, 80))).append("`\n");
        }
        if (rid != null && !rid.isBlank()) {
            sb.append("- x-request-id: `").append(escapeMd(safeInline(rid, 80))).append("`\n");
        }

        sb.append("\n**Plan / Mode**\n");
        if (planId != null && !planId.isBlank()) {
            sb.append("- plan.id: `").append(escapeMd(safeInline(planId, 120))).append("`\n");
        }
        if (planOrder != null && !planOrder.isBlank() && !"null".equalsIgnoreCase(planOrder)) {
            sb.append("- plan.order: `").append(escapeMd(planOrder)).append("`\n");
        }
        if (orchMode != null && !orchMode.isBlank()) {
            sb.append("- orch.mode: `").append(escapeMd(safeInline(orchMode, 80))).append("`\n");
        }
        if (orchReason != null && !orchReason.isBlank() && !"null".equalsIgnoreCase(orchReason)) {
            sb.append("- orch.reason: ").append(escapeMd(orchReason)).append("\n");
        }
        sb.append("- flags: strike=").append(strike)
                .append(", compression=").append(compression)
                .append(", bypass=").append(bypass)
                .append("\n");

        sb.append("\n**WebFailSoft (KPI)**\n");
        if (!executedQuery.isBlank() && !"null".equalsIgnoreCase(executedQuery)) {
            sb.append("- executedQuery: `").append(escapeMd(executedQuery)).append("`\n");
        }
        if (!extraQuery.isBlank() && !"null".equalsIgnoreCase(extraQuery)) {
            sb.append("- extraQuery/rescueQuery: `").append(escapeMd(extraQuery)).append("`\n");
        }
        if (!outCount.isBlank() && !"null".equalsIgnoreCase(outCount)) {
            sb.append("- outCount: ").append(escapeMd(outCount)).append("\n");
        }
        if (stageCounts != null && !stageCounts.isBlank()) {
            sb.append("- stageCountsSelectedFromOut: ").append(escapeMd(stageCounts)).append("\n");
        }
        if (starvationFallback != null && !starvationFallback.isBlank()
                && !"null".equalsIgnoreCase(starvationFallback)) {
            sb.append("- starvationFallback: `").append(escapeMd(starvationFallback)).append("`\n");
        }
        if (!starvationTrigger.isBlank() && !"null".equalsIgnoreCase(starvationTrigger)) {
            sb.append("- starvationFallback.trigger: `").append(escapeMd(starvationTrigger)).append("`\n");
        }
        if (!poolUsed.isBlank() && !"null".equalsIgnoreCase(poolUsed)) {
            sb.append("- starvationFallback.poolUsed: `").append(escapeMd(poolUsed)).append("`\n");
        }
        if (!poolSafeEmpty.isBlank() && !"null".equalsIgnoreCase(poolSafeEmpty)) {
            sb.append("- poolSafeEmpty: ").append(escapeMd(poolSafeEmpty)).append("\n");
        }
        if (!cacheMerged.isBlank() && !"null".equalsIgnoreCase(cacheMerged)) {
            sb.append("- cacheOnly.merged.count: ").append(escapeMd(cacheMerged)).append("\n");
        }
        sb.append("- starvationFallback.used: ").append(starvationUsed).append("\n");

        sb.append("\n**Providers / Await / Breaker**\n");
        if (!webHardDown.isBlank() && !"null".equalsIgnoreCase(webHardDown)) {
            sb.append("- web.hardDown: ").append(escapeMd(webHardDown)).append("\n");
        }
        if (!awaitSkippedCount.isBlank() && !"null".equalsIgnoreCase(awaitSkippedCount)) {
            sb.append("- web.await.skipped.count: ").append(escapeMd(awaitSkippedCount)).append("\n");
        }
        if ((!awaitRootEngine.isBlank() && !"null".equalsIgnoreCase(awaitRootEngine))
                || (!awaitRootCause.isBlank() && !"null".equalsIgnoreCase(awaitRootCause))
                || (!awaitRootWaitedMs.isBlank() && !"null".equalsIgnoreCase(awaitRootWaitedMs))) {
            sb.append("- web.await.root: engine=`").append(escapeMd(zeroIfBlank(awaitRootEngine))).append("` ")
                    .append("cause=`").append(escapeMd(zeroIfBlank(awaitRootCause))).append("` ")
                    .append("waitedMs=").append(escapeMd(zeroIfBlank(awaitRootWaitedMs)))
                    .append("\n");
        }


        // Single-line operator surface (requested keys)
        boolean hasDisabledReason = (awaitBraveDisabledReason != null && !awaitBraveDisabledReason.isBlank()
                && !"null".equalsIgnoreCase(awaitBraveDisabledReason));
        boolean hasNaverDemoteReason = (naverStrictDomainRequiredDemotedReason != null
                && !naverStrictDomainRequiredDemotedReason.isBlank()
                && !"null".equalsIgnoreCase(naverStrictDomainRequiredDemotedReason));
        if (orchWebPartialDownAnyDown || hasDisabledReason || naverStrictDomainRequiredDemoted) {
            sb.append("- web.partialDown: orch.webPartialDown.anyDown=").append(orchWebPartialDownAnyDown)
                    .append(", web.await.brave.disabledReason=`")
                    .append(escapeMd(hasDisabledReason ? awaitBraveDisabledReason : "-"))
                    .append("`")
                    .append(", web.naver.strictDomainRequiredDemoted=").append(naverStrictDomainRequiredDemoted);
            if (hasNaverDemoteReason) {
                sb.append(" (reason=").append(escapeMd(naverStrictDomainRequiredDemotedReason)).append(")");
            }
            sb.append("\n");
        }
        if ((!braveSkippedCount.isBlank() && !"null".equalsIgnoreCase(braveSkippedCount))
                || (!braveSkippedReason.isBlank() && !"null".equalsIgnoreCase(braveSkippedReason))
                || (!braveSkippedExtraMs.isBlank() && !"null".equalsIgnoreCase(braveSkippedExtraMs))) {
            sb.append("- brave.skipped: count=").append(escapeMd(zeroIfBlank(braveSkippedCount)))
                    .append(", reason=`").append(escapeMd(zeroIfBlank(braveSkippedReason)))
                    .append("`, extraMs=").append(escapeMd(zeroIfBlank(braveSkippedExtraMs)))
                    .append("\n");
        }
        if ((!naverSkippedCount.isBlank() && !"null".equalsIgnoreCase(naverSkippedCount))
                || (!naverSkippedReason.isBlank() && !"null".equalsIgnoreCase(naverSkippedReason))
                || (!naverSkippedExtraMs.isBlank() && !"null".equalsIgnoreCase(naverSkippedExtraMs))) {
            sb.append("- naver.skipped: count=").append(escapeMd(zeroIfBlank(naverSkippedCount)))
                    .append(", reason=`").append(escapeMd(zeroIfBlank(naverSkippedReason)))
                    .append("`, extraMs=").append(escapeMd(zeroIfBlank(naverSkippedExtraMs)))
                    .append("\n");
        }

        sb.append("\n**Citations (minCitations gate)**\n");
        if (!officialOnly.isBlank() && !"null".equalsIgnoreCase(officialOnly)) {
            sb.append("- officialOnly: ").append(escapeMd(officialOnly)).append("\n");
        }
        if (!minCitations.isBlank() && !"null".equalsIgnoreCase(minCitations)) {
            sb.append("- minCitations(plan): ").append(escapeMd(minCitations)).append("\n");
        }
        if (!minCitationsNeeded.isBlank() && !"null".equalsIgnoreCase(minCitationsNeeded)) {
            sb.append("- minCitationsNeeded: ").append(escapeMd(minCitationsNeeded)).append("\n");
        }
        if (!citeableCount.isBlank() && !"null".equalsIgnoreCase(citeableCount)) {
            sb.append("- citeableCount: ").append(escapeMd(citeableCount)).append("\n");
        }
        if (!citeableTopUpUsed.isBlank() && !"null".equalsIgnoreCase(citeableTopUpUsed)) {
            sb.append("- citeableTopUp.used: ").append(escapeMd(citeableTopUpUsed)).append(" (added=")
                    .append(escapeMd(zeroIfBlank(citeableTopUpAdded))).append(", tailDrop=")
                    .append(escapeMd(zeroIfBlank(citeableTopUpTailDrop))).append(")\n");
        }
        if (!minCitationsRescueAttempted.isBlank() && !"null".equalsIgnoreCase(minCitationsRescueAttempted)) {
            sb.append("- minCitationsRescue.attempted: ").append(escapeMd(minCitationsRescueAttempted))
                    .append(" (inserted=").append(escapeMd(zeroIfBlank(minCitationsRescueInserted)))
                    .append(", satisfied=").append(escapeMd(zeroIfBlank(minCitationsRescueSatisfied))).append(")\n");
        }

        sb.append("\n**Interrupt / cancellation hygiene**\n");
        if (!cancelTrue.isBlank() && !"null".equalsIgnoreCase(cancelTrue)) {
            sb.append("- cancel(true) requested: ").append(escapeMd(cancelTrue)).append("\n");
        }
        if (!downgraded.isBlank() && !"null".equalsIgnoreCase(downgraded)) {
            sb.append("- cancel(true)->cancel(false) downgraded: ").append(escapeMd(downgraded)).append("\n");
        }
        if (!softCancelled.isBlank() && !"null".equalsIgnoreCase(softCancelled)) {
            sb.append("- softCancelled (delegate threw): ").append(escapeMd(softCancelled)).append("\n");
        }

        if ((!invokeAllTimedOut.isBlank() && !"null".equalsIgnoreCase(invokeAllTimedOut))
                || (!invokeAllCancelAttempted.isBlank() && !"null".equalsIgnoreCase(invokeAllCancelAttempted))
                || (!invokeAllCancelSucceeded.isBlank() && !"null".equalsIgnoreCase(invokeAllCancelSucceeded))
                || (!invokeAllMaxInflight.isBlank() && !"null".equalsIgnoreCase(invokeAllMaxInflight))
                || (!invokeAllSubmitted.isBlank() && !"null".equalsIgnoreCase(invokeAllSubmitted))
                || (!invokeAllCompleted.isBlank() && !"null".equalsIgnoreCase(invokeAllCompleted))) {
            sb.append("- invokeAll(timeout): timedOut=").append(escapeMd(zeroIfBlank(invokeAllTimedOut)))
                    .append(", maxInflight=").append(escapeMd(zeroIfBlank(invokeAllMaxInflight)))
                    .append(", submitted=").append(escapeMd(zeroIfBlank(invokeAllSubmitted)))
                    .append(", completed=").append(escapeMd(zeroIfBlank(invokeAllCompleted)))
                    .append(", cancelAttempted=").append(escapeMd(zeroIfBlank(invokeAllCancelAttempted)))
                    .append(", cancelSucceeded=").append(escapeMd(zeroIfBlank(invokeAllCancelSucceeded)))
                    .append("\n");
        }
        if ((!invokeAnySuccess.isBlank() && !"null".equalsIgnoreCase(invokeAnySuccess))
                || (!invokeAnyTimedOut.isBlank() && !"null".equalsIgnoreCase(invokeAnyTimedOut))
                || (!invokeAnySubmitted.isBlank() && !"null".equalsIgnoreCase(invokeAnySubmitted))
                || (!invokeAnyCancelAttempted.isBlank() && !"null".equalsIgnoreCase(invokeAnyCancelAttempted))
                || (!invokeAnyCancelSucceeded.isBlank() && !"null".equalsIgnoreCase(invokeAnyCancelSucceeded))) {
            sb.append("- invokeAny: success=").append(escapeMd(zeroIfBlank(invokeAnySuccess)))
                    .append(", timedOut=").append(escapeMd(zeroIfBlank(invokeAnyTimedOut)))
                    .append(", submitted=").append(escapeMd(zeroIfBlank(invokeAnySubmitted)))
                    .append(", cancelAttempted=").append(escapeMd(zeroIfBlank(invokeAnyCancelAttempted)))
                    .append(", cancelSucceeded=").append(escapeMd(zeroIfBlank(invokeAnyCancelSucceeded)))
                    .append("\n");
        }

        if ((!webIhEntryCleared.isBlank() && !"null".equalsIgnoreCase(webIhEntryCleared))
                || (!webIhExitCleared.isBlank() && !"null".equalsIgnoreCase(webIhExitCleared))) {
            sb.append("- web.interruptHygiene.cleared: entry=").append(escapeMd(zeroIfBlank(webIhEntryCleared)))
                    .append(", exit=").append(escapeMd(zeroIfBlank(webIhExitCleared)))
                    .append("\n");
        }
        if ((!naverIhEntryCleared.isBlank() && !"null".equalsIgnoreCase(naverIhEntryCleared))
                || (!naverIhExitCleared.isBlank() && !"null".equalsIgnoreCase(naverIhExitCleared))
                || (!naverIhSwallowed.isBlank() && !"null".equalsIgnoreCase(naverIhSwallowed))) {
            sb.append("- naver.interruptHygiene: cleared(entry=")
                    .append(escapeMd(zeroIfBlank(naverIhEntryCleared)))
                    .append(", exit=").append(escapeMd(zeroIfBlank(naverIhExitCleared)))
                    .append(") swallowed=").append(escapeMd(zeroIfBlank(naverIhSwallowed)))
                    .append("\n");
        }

        sb.append("\n**Aux**\n");
        if (auxKeywordSel != null && !auxKeywordSel.isBlank() && !"null".equalsIgnoreCase(auxKeywordSel)) {
            sb.append("- aux.keywordSelection: `").append(escapeMd(auxKeywordSel)).append("`\n");
        }
        if (auxKeywordSelReason != null && !auxKeywordSelReason.isBlank()
                && !"null".equalsIgnoreCase(auxKeywordSelReason)) {
            sb.append("- aux.keywordSelection.degraded.reason: ").append(escapeMd(auxKeywordSelReason)).append("\n");
        }
        if (auxQtx != null && !auxQtx.isBlank() && !"null".equalsIgnoreCase(auxQtx)) {
            sb.append("- aux.queryTransformer: `").append(escapeMd(auxQtx)).append("`\n");
        }
        if (!auxQtxDegradedReason.isBlank() && !"null".equalsIgnoreCase(auxQtxDegradedReason)) {
            sb.append("- aux.queryTransformer.degraded.reason: ").append(escapeMd(auxQtxDegradedReason)).append("\n");
        }
        if (!auxQtxDegradedCount.isBlank() && !"null".equalsIgnoreCase(auxQtxDegradedCount)) {
            sb.append("- aux.queryTransformer.degraded.count: ").append(escapeMd(auxQtxDegradedCount)).append("\n");
        }

        sb.append("\n**Guard**\n");
        if (guardAction != null && !guardAction.isBlank() && !"null".equalsIgnoreCase(guardAction)) {
            sb.append("- guard.final.action: `").append(escapeMd(guardAction)).append("`\n");
        }
        if (guardNote != null && !guardNote.isBlank() && !"null".equalsIgnoreCase(guardNote)) {
            sb.append("- guard.note: ").append(escapeMd(guardNote)).append("\n");
        }

        if (autoReportText != null && !autoReportText.isBlank() && !"null".equalsIgnoreCase(autoReportText)) {
            sb.append("\n**Auto report (orch.autoReport.text)**\n");
            sb.append("```\n").append(autoReportText).append("\n```\n");
        }
        appendOrchTimeline(sb, ctx.get("orch.events.v1"), 12);

        if (!fixHints.isEmpty()) {
            sb.append("\n**Fix hints (auto)**\n");
            int limit = Math.min(6, fixHints.size());
            for (int i = 0; i < limit; i++) {
                sb.append("- ").append(escapeMd(fixHints.get(i))).append("\n");
            }
            if (fixHints.size() > limit) {
                sb.append("- … (").append(fixHints.size() - limit).append(" more)\n");
            }
        }

        // Selected keys for copy/paste (debug-friendly)
        if (dbg && includeSelectedKeys) {
            sb.append("\n**TraceStore keys (selected)**\n");
            sb.append("```\n");
            for (String k : selectedKeys()) {
                if (k == null)
                    continue;
                Object v = ctx.get(k);
                if (v == null)
                    continue;
                sb.append(k).append("=").append(safeInline(v, 240)).append("\n");
            }
            sb.append("```\n");
        }

        String rendered = sb.toString();
        if (redact) {
            rendered = SafeRedactor.redact(rendered);
        }
        rendered = clipAndCloseDetails(rendered, maxLines, maxChars);
        return rendered;
    }

    private static String zeroIfBlank(String s) {
        if (s == null)
            return "0";
        String t = s.trim();
        if (t.isBlank() || "null".equalsIgnoreCase(t))
            return "0";
        return t;
    }

    private static List<String> buildFixHints(Map<String, Object> ctx) {
        List<String> out = new ArrayList<>();

        boolean starvationUsed = truthy(firstNonNull(
                ctx.get("web.failsoft.starvationFallback.used"),
                ctx.get("starvationFallback.used"),
                ctx.get("web.failsoft.starvationFallback")));
        if (starvationUsed) {
            out.add("WEB starvationFallback 경로가 사용됨 → strict/officialOnly 필터 과도 또는 provider failure 누적 가능. stageCountsSelectedFromOut로 OFFICIAL/DOCS 확보 여부를 확인하세요.");
        }

        boolean webHardDown = truthy(firstNonNull(
                ctx.get("web.hardDown"),
                ctx.get("orch.webRateLimited.effective"),
                ctx.get("orch.webRateLimited")))
                || toLong(firstNonNull(ctx.get("web.await.skipped.count"), ctx.get("web.await.skipped"))) >= 2;
        if (webHardDown) {
            out.add("WEB hard-down/rate-limit 징후 → breaker OPEN(web.<provider>.skipped.reason=breaker_open) 또는 timeout/429 과집계 여부를 점검하세요.");
        }

        String braveReason = safeInline(ctx.get("web.brave.skipped.reason"), 120);
        String naverReason = safeInline(ctx.get("web.naver.skipped.reason"), 120);
        if (containsIgnoreCase(braveReason, "breaker_open") || containsIgnoreCase(naverReason, "breaker_open")) {
            out.add("provider breaker_open 감지(brave/naver) → breaker 전염/과집계(429/timeout/cancelled) 여부와 provider별 breaker 분리/쿨다운 정책을 확인하세요.");
        }

        String auxKeywordSel = safeInline(firstNonNull(ctx.get("aux.keywordSelection"), ctx.get("keywordSelection")),
                240);
        if (containsIgnoreCase(auxKeywordSel, "degraded") || containsIgnoreCase(auxKeywordSel, "blank")) {
            out.add("keywordSelection degraded/blank → MUST 부족이 재발할 수 있음. nova.orch.keyword-selection.force-min-must.* 활성화/로그 키(aux.keywordSelection.forceMinMust.*)로 추적하세요.");
        }

        String qtx = safeInline(firstNonNull(ctx.get("aux.queryTransformer"), ctx.get("qtx")), 240);
        if (containsIgnoreCase(qtx, "breaker") || containsIgnoreCase(qtx, "timeout")
                || containsIgnoreCase(qtx, "hint")) {
            out.add("queryTransformer 불안정/OPEN → raw query fail-soft 우회가 필요합니다(변환 실패 시 즉시 원문으로 검색).");
        }

        String guardAction = safeInline(firstNonNull(ctx.get("guard.final.action"), ctx.get("guard.action")), 128);
        if (containsIgnoreCase(guardAction, "DEGRADE") || containsIgnoreCase(guardAction, "evidence")) {
            out.add("Guard가 evidence list로 degrade → 인용/근거 부족. minCitationsRescue/citeableTopUp/officialOnly 정책 및 web.failsoft KPIs를 확인하세요.");
        }

        String downgraded = safeInline(firstNonNull(ctx.get("ops.cancelShield.downgraded.count")), 24);
        if (!downgraded.isBlank() && !"null".equalsIgnoreCase(downgraded) && !"0".equals(downgraded.trim())) {
            out.add("CancelShield가 cancel(true) → cancel(false) downgrade를 수행함 → interrupt-poisoning 완화 신호(추가로 invokeAny/timeout 경로를 점검).");
        }

        boolean invokeAllTimedOut = truthy(firstNonNull(ctx.get("ops.cancelShield.invokeAll.timeout.timedOut")));
        boolean invokeAnyTimedOut = truthy(firstNonNull(ctx.get("ops.cancelShield.invokeAny.timedOut")));
        if (invokeAllTimedOut || invokeAnyTimedOut) {
            out.add("invokeAll(timeout)/invokeAny timeout 발생 → CancelShield는 cancel(false)로 interrupt 독성 전염을 막지만, 백그라운드 지속 가능. 각 작업의 HTTP/I/O timeout + 단계별 deadline(softMs/deadlineCapMs) 강제를 권장합니다.");
        }

        // Correlation / observability
        String rid = safeInline(firstNonNull(ctx.get("x-request-id"), ctx.get("trace"), ctx.get("traceId")), 80);
        String sid = safeInline(firstNonNull(ctx.get("sid"), ctx.get("sessionId")), 80);
        if (containsIgnoreCase(rid, "rid-missing") || containsIgnoreCase(sid, "sid-missing")
                || truthy(firstNonNull(ctx.get("ctx.propagation.missing"), ctx.get("ctx.correlation.missing")))) {
            out.add("상관관계 ID 누락(CtxMissing) → sid/x-request-id가 MDC/TraceStore에 유지되는지 확인(재현성/디버깅 효율에 중요).");
        }

        return out;
    }

    private static String renderStageCounts(Object stageCountsObj) {
        if (stageCountsObj == null) {
            return "";
        }
        if (stageCountsObj instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Object k : m.keySet()) {
                if (k == null)
                    continue;
                keys.add(String.valueOf(k));
            }
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (String k : keys) {
                Object v = m.get(k);
                if (v == null)
                    continue;
                if (shown++ > 0)
                    sb.append(", ");
                sb.append(k).append("=").append(String.valueOf(v));
                if (shown >= 12) {
                    sb.append(", …");
                    break;
                }
            }
            return sb.toString();
        }
        return safeInline(stageCountsObj, 240);
    }

    private static List<String> selectedKeys() {
        return List.of(
                "sid",
                "x-request-id",
                "plan.id",
                "plan.order",
                "plan.minCitations",
                "plan.officialOnly",
                "orch.mode",
                "orch.reason",
                "orch.strike",
                "orch.compression",
                "orch.bypass",
                "guard.final.action",
                "guard.final.note",
                "guard.degrade.reason",
                "guard.forceEscalateOverDegrade",
                "guard.forceEscalateOverDegrade.by",
                "guard.forceEscalateOverDegrade.reason",
                "guard.forceEscalateOverDegrade.trigger",
                "guard.forceEscalateOverDegrade.highRisk",
                "guard.forceEscalateOverDegrade.blocked",
                "guard.forceEscalateOverDegrade.blocked.reason",
                "guard.detour.forceEscalate",
                "guard.detour.forceEscalate.by",
                "guard.detour.forceEscalate.reason",
                "guard.detour.forceEscalate.trigger",
                "guard.detour.forceEscalate.blocked",
                "guard.detour.forceEscalate.blocked.reason",
                "guard.detour.cheapRetry.forceEscalate",
                "guard.detour.cheapRetry.forceEscalate.by",
                "guard.detour.cheapRetry.forceEscalate.trigger",
                "guard.detour.cheapRetry.forceEscalate.entityLike",
                "guard.detour.cheapRetry.regen",
                "guard.detour.cheapRetry.failed.reason",
                "aux.keywordSelection",
                "aux.keywordSelection.degraded",
                "aux.keywordSelection.degraded.reason",
                "keywordSelection.fallback.seedSource",
                "keywordSelection.fallback.seed",
                "keywordSelection.fallback.seed.baseScore",
                "keywordSelection.fallback.seed.uqScore",
                "keywordSelection.fallback.trimmedBase",
                "keywordSelection.fallback.entityPhrase",
                "keywordSelection.fallback.exact",
                "keywordSelection.fallback.exact.source",
                "aux.queryTransformer",
                "aux.queryTransformer.degraded",
                "aux.queryTransformer.degraded.reason",
                "aux.queryTransformer.degraded.count",
                "qtx.userPrompt.recovered",
                "qtx.userPrompt.recovered.source",
                "qtx.normalized.blankRecovered",
                "qtx.normalized.blankRecovered.source",
                "qtx.cheapFallback.recovered",
                "qtx.cheapFallback.recovered.source",
                "qtx.softCooldown.active",
                "qtx.softCooldown.remainingMs",
                "orch.autoReport.text",
                "orch.events.v1",
                "web.failsoft.executedQuery",
                "web.failsoft.extraQuery",
                "web.failsoft.starvationFallback",
                "web.failsoft.starvationFallback.trigger",
                "web.failsoft.starvationFallback.poolUsed",
                "web.failsoft.starvationFallback.used",
                "web.failsoft.starvationFallback.poolSafeEmpty",
                "web.failsoft.minCitationsNeeded",
                "web.failsoft.citeableCount",
                "web.failsoft.citeableTopUp.used",
                "web.failsoft.citeableTopUp.added",
                "web.failsoft.citeableTopUp.tailDrop.count",
                "web.failsoft.minCitationsRescue.attempted",
                "web.failsoft.minCitationsRescue.insertedCount",
                "web.failsoft.minCitationsRescue.satisfied",
                "web.failsoft.minCitationsRescue.stagedWasEmpty",
                "web.failsoft.minCitationsRescue.deficit",
                "web.failsoft.minCitationsRescue.calls.issued",
                "web.failsoft.minCitationsRescue.citeableCount.after",
                "cacheOnly.merged.count",
                "web.failsoft.stageCountsSelectedFromOut",
                "outCount",
                "ops.cancelShield.cancelTrue.count",
                "ops.cancelShield.downgraded.count",
                "ops.cancelShield.softCancelled.count",
                "ops.cancelShield.invokeAll.timeout.used",
                "ops.cancelShield.invokeAll.timeout.tasks",
                "ops.cancelShield.invokeAll.timeout.timeoutNs",
                "ops.cancelShield.invokeAll.timeout.timedOut",
                "ops.cancelShield.invokeAll.timeout.interrupted",
                "ops.cancelShield.invokeAll.timeout.cancelAttempted",
                "ops.cancelShield.invokeAll.timeout.cancelSucceeded",
                "ops.cancelShield.invokeAny.used",
                "ops.cancelShield.invokeAny.timed",
                "ops.cancelShield.invokeAny.tasks",
                "ops.cancelShield.invokeAny.submitted",
                "ops.cancelShield.invokeAny.timedOut",
                "ops.cancelShield.invokeAny.success",
                "ops.cancelShield.invokeAny.cancelAttempted",
                "ops.cancelShield.invokeAny.cancelSucceeded",
                "web.hybrid.braveJoin.policy",
                "web.hybrid.braveJoin.mode",
                "web.hybrid.braveJoin.deadlineCapMs",
                "web.hybrid.braveJoin.soft",
                "web.hybrid.braveJoin.softMs",
                "web.hardDown",
                "web.await.skipped.count",
                "web.await.root.engine",
                "web.await.root.cause",
                "web.await.root.waitedMs",
                "web.await.events.summary.engine.Naver.cause.await_timeout.count",
                "web.await.events.summary.engine.Brave.cause.await_timeout.count",
                "orch.webPartialDown",
                "orch.webPartialDown.anyDown",
                "web.await.brave.disabledReason",
                "web.naver.strictDomainRequiredDemoted",
                "web.naver.strictDomainRequiredDemoted.reason",
                "web.brave.skipped.count",
                "web.brave.skipped.reason",
                "web.brave.skipped.extraMs",
                "web.failsoft.rateLimitBackoff.naver.awaitTimeoutApplied",
                "web.failsoft.rateLimitBackoff.brave.awaitTimeoutApplied",
                "web.naver.skipped.count",
                "web.naver.skipped.reason",
                "web.naver.skipped.extraMs",
                "web.interruptHygiene.cleared.entry.count",
                "web.interruptHygiene.cleared.exit.count",
                "naver.interruptHygiene.cleared.entry.count",
                "naver.interruptHygiene.cleared.exit.count",
                "naver.interruptHygiene.swallowed.count",
                "aux.keywordSelection.forceMinMust.applied",
                "aux.keywordSelection.forceMinMust.reason",
                "aux.keywordSelection.forceMinMust.before",
                "aux.keywordSelection.forceMinMust.after");
    }

    private static Object firstNonNull(Object... vs) {
        if (vs == null)
            return null;
        for (Object v : vs) {
            if (v != null)
                return v;
        }
        return null;
    }

    private static String getString(Map<String, Object> ctx, String key) {
        if (ctx == null || key == null)
            return null;
        Object v = ctx.get(key);
        if (v == null)
            return null;
        return String.valueOf(v);
    }

    private static String firstNonBlank(String... ss) {
        if (ss == null)
            return null;
        for (String s : ss) {
            if (s != null && !s.isBlank())
                return s;
        }
        return null;
    }

    private static boolean truthy(Object v) {
        if (v == null)
            return false;
        if (v instanceof Boolean b)
            return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isBlank())
            return false;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static long toLong(Object v) {
        if (v == null)
            return 0L;
        if (v instanceof Number n)
            return n.longValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s))
                return 0L;
            return Long.parseLong(s);
        } catch (Exception ignore) {
            return 0L;
        }
    }

    private static String safeInline(Object v, int max) {
        if (v == null)
            return "";
        String s = String.valueOf(v);
        if (s == null)
            return "";
        s = s.replace("\r\n", " ").replace("\n", " ").replace("\r", " ").trim();
        if (max > 0 && s.length() > max) {
            s = s.substring(0, max);
        }
        return s;
    }

    private static String safeBlock(Object v, int maxChars) {
        if (v == null)
            return "";
        String s = String.valueOf(v);
        if (s == null)
            return "";
        s = s.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (maxChars > 0 && s.length() > maxChars) {
            s = s.substring(0, maxChars);
        }
        return s;
    }

    private static void appendOrchTimeline(StringBuilder out, Object eventsObj, int maxEvents) {
        if (out == null || maxEvents <= 0 || eventsObj == null)
            return;
        if (!(eventsObj instanceof List<?> events) || events.isEmpty())
            return;

        int limit = Math.min(maxEvents, events.size());
        int start = Math.max(0, events.size() - limit);

        out.append("\n**Timeline (orch.events.v1, last ").append(limit).append(")**\n");
        for (int i = start; i < events.size(); i++) {
            String line = renderOrchEventLine(events.get(i));
            if (line == null || line.isBlank())
                continue;
            out.append("- ").append(escapeMd(line)).append("\n");
        }
    }

    private static String renderOrchEventLine(Object eventObj) {
        if (eventObj == null)
            return "";
        if (eventObj instanceof Map<?, ?> m) {
            String seq = safeInline(m.get("seq"), 12);
            String ts = safeInline(m.get("ts"), 30);
            String kind = safeInline(m.get("kind"), 16);
            String phase = safeInline(m.get("phase"), 20);
            String step = safeInline(m.get("step"), 20);
            String data = safeInline(m.get("data"), 140);

            StringBuilder sb = new StringBuilder();
            if (!seq.isBlank() && !"null".equalsIgnoreCase(seq)) {
                sb.append("#").append(seq).append(" ");
            }
            if (!ts.isBlank() && !"null".equalsIgnoreCase(ts)) {
                sb.append(ts).append(" ");
            }
            if (!kind.isBlank() && !"null".equalsIgnoreCase(kind)) {
                sb.append(kind);
            }
            if ((!phase.isBlank() && !"null".equalsIgnoreCase(phase))
                    || (!step.isBlank() && !"null".equalsIgnoreCase(step))) {
                sb.append("/").append(zeroIfBlank(phase));
                if (!zeroIfBlank(step).isBlank()) {
                    sb.append("/").append(zeroIfBlank(step));
                }
            }
            if (!data.isBlank() && !"null".equalsIgnoreCase(data)) {
                sb.append(" ").append(data);
            }
            return sb.toString().trim();
        }
        return safeInline(eventObj, 200);
    }

    private static String escapeMd(String s) {
        if (s == null)
            return "";
        // Minimal markdown escaping for inline contexts.
        return s.replace("`", "\\`");
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        if (s == null || needle == null || needle.isEmpty())
            return false;
        return s.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
