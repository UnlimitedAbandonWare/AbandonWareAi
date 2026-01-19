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
 * Injects a compact TraceStore summary into EvidenceAwareGuard.degradeToEvidenceList() output.
 *
 * <p>
 * Motivation:
 * - When the guard degrades to an evidence list, the user-facing output is often "URLs only".
 * - For reproducibility / debugging we want Plan/Mode + Aux/Guard + WebFailSoft stage summaries.
 *
 * <p>
 * Safety:
 * - Only selected keys (orch/aux/guard/plan + web.failsoft KPI) are shown.
 * - All values are passed through {@link SafeRedactor} and clipped.
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
        if (!enabled()) {
            return ret;
        }
        if (base.contains(MARKER)) {
            return ret;
        }


        String diag = buildDiagnosticsBlock();
        if (diag == null || diag.isBlank()) {
            return ret;
        }
        return base + "\n\n" + MARKER + "\n" + diag;
    }

    /**
     * When the main LLM path times out (LLM_FAST_BAIL_TIMEOUT) the system may fall back to a
     * deterministic evidence-based answer via {@code EvidenceAnswerComposer}. That path does not
     * go through {@code EvidenceAwareGuard.degradeToEvidenceList(..)}, so we inject the same
     * diagnostics block here as well.
     */
    @Around("execution(public String com.example.lms.service.rag.EvidenceAnswerComposer.compose(..))")
    public Object aroundEvidenceAnswerComposer(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();
        if (!(ret instanceof String base)) {
            return ret;
        }
        if (!enabled()) {
            return ret;
        }
        if (base.contains(MARKER)) {
            return ret;
        }


        String diag = buildDiagnosticsBlock();
        if (diag == null || diag.isBlank()) {
            return ret;
        }
        return base + "\n\n" + MARKER + "\n" + diag;
    }

    private boolean enabled() {
        return env.getProperty("nova.orch.evidence-list.trace-injection.enabled", Boolean.class, true);
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
        if (!s.contains("</details>")) {
            s = s + "\n</details>\n";
        }

        return s;
    }

    private static int countOccurrences(String s, String needle) {
        if (s == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while (true) {
            idx = s.indexOf(needle, idx);
            if (idx < 0) break;
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
        // Keep <details> CLOSED by default; do not auto-open based on debug flags.
        boolean open = getBool("nova.orch.evidence-list.trace-injection.details-open",
                "nova.orch.evidence-list.trace-injection.detailsOpen", false);

        boolean includeSelectedKeys = getBool("nova.orch.evidence-list.trace-injection.include-selected-keys",
                "nova.orch.evidence-list.trace-injection.includeSelectedKeys", false);
        boolean redact = getBool("nova.orch.evidence-list.trace-injection.redact",
                "nova.orch.evidence-list.trace-injection.redaction", true);
        int maxLines = getInt("nova.orch.evidence-list.trace-injection.max-lines",
                "nova.orch.evidence-list.trace-injection.maxLines", 60);
        int maxChars = getInt("nova.orch.evidence-list.trace-injection.max-chars",
                "nova.orch.evidence-list.trace-injection.maxChars", 3000);

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

        // ---- Guard ----
        String guardAction = safeInline(firstNonNull(
                ctx.get("guard.final.action"),
                ctx.get("guard.action")), 128);
        String guardNote = safeInline(firstNonNull(
                ctx.get("guard.final.note"),
                ctx.get("guard.note")), 240);

        List<String> fixHints = buildFixHints(ctx);

        StringBuilder sb = new StringBuilder(1024);
        sb.append("---\n");
        sb.append(open ? "<details open>\n" : "<details>\n");
        sb.append("<summary>🔎 라우팅/진단 요약 (Plan/Mode · Aux/Guard · WebFailSoft)</summary>\n\n");

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
        if (!outCount.isBlank() && !"null".equalsIgnoreCase(outCount)) {
            sb.append("- outCount: ").append(escapeMd(outCount)).append("\n");
        }
        if (stageCounts != null && !stageCounts.isBlank()) {
            sb.append("- stageCountsSelectedFromOut: ").append(escapeMd(stageCounts)).append("\n");
        }
        if (starvationFallback != null && !starvationFallback.isBlank() && !"null".equalsIgnoreCase(starvationFallback)) {
            sb.append("- starvationFallback: `").append(escapeMd(starvationFallback)).append("`\n");
        }
        if (!poolSafeEmpty.isBlank() && !"null".equalsIgnoreCase(poolSafeEmpty)) {
            sb.append("- poolSafeEmpty: ").append(escapeMd(poolSafeEmpty)).append("\n");
        }
        if (!cacheMerged.isBlank() && !"null".equalsIgnoreCase(cacheMerged)) {
            sb.append("- cacheOnly.merged.count: ").append(escapeMd(cacheMerged)).append("\n");
        }
        sb.append("- starvationFallback.used: ").append(starvationUsed).append("\n");

        sb.append("\n**Aux**\n");
        if (auxKeywordSel != null && !auxKeywordSel.isBlank() && !"null".equalsIgnoreCase(auxKeywordSel)) {
            sb.append("- aux.keywordSelection: `").append(escapeMd(auxKeywordSel)).append("`\n");
        }
        if (auxKeywordSelReason != null && !auxKeywordSelReason.isBlank() && !"null".equalsIgnoreCase(auxKeywordSelReason)) {
            sb.append("- aux.keywordSelection.degraded.reason: ").append(escapeMd(auxKeywordSelReason)).append("\n");
        }
        if (auxQtx != null && !auxQtx.isBlank() && !"null".equalsIgnoreCase(auxQtx)) {
            sb.append("- aux.queryTransformer: `").append(escapeMd(auxQtx)).append("`\n");
        }

        sb.append("\n**Guard**\n");
        if (guardAction != null && !guardAction.isBlank() && !"null".equalsIgnoreCase(guardAction)) {
            sb.append("- guard.final.action: `").append(escapeMd(guardAction)).append("`\n");
        }
        if (guardNote != null && !guardNote.isBlank() && !"null".equalsIgnoreCase(guardNote)) {
            sb.append("- guard.note: ").append(escapeMd(guardNote)).append("\n");
        }

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
                if (k == null) continue;
                Object v = ctx.get(k);
                if (v == null) continue;
                sb.append(k).append("=").append(safeInline(v, 240)).append("\n");
            }
            sb.append("```\n");
        }

        sb.append("\n</details>\n");

        // Observability breadcrumb
        try {
            TraceStore.put("orch.evidenceList.traceInjected", true);
            TraceStore.put("orch.evidenceList.traceInjected.open", open);
        } catch (Throwable ignore) {
            // best-effort
        }

        String rendered = sb.toString();
        if (redact) {
            rendered = SafeRedactor.redact(rendered);
        }
        rendered = clipAndCloseDetails(rendered, maxLines, maxChars);
        return rendered;
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

        String auxKeywordSel = safeInline(firstNonNull(ctx.get("aux.keywordSelection"), ctx.get("keywordSelection")), 240);
        if (containsIgnoreCase(auxKeywordSel, "degraded") || containsIgnoreCase(auxKeywordSel, "blank")) {
            out.add("keywordSelection degraded/blank → MUST 부족이 재발할 수 있음. nova.orch.keyword-selection.force-min-must.* 활성화/로그 키(aux.keywordSelection.forceMinMust.*)로 추적하세요.");
        }

        String qtx = safeInline(firstNonNull(ctx.get("aux.queryTransformer"), ctx.get("qtx")), 240);
        if (containsIgnoreCase(qtx, "breaker") || containsIgnoreCase(qtx, "timeout") || containsIgnoreCase(qtx, "hint")) {
            out.add("queryTransformer 불안정/OPEN → raw query fail-soft 우회가 필요합니다(변환 실패 시 즉시 원문으로 검색).");
        }

        String guardAction = safeInline(firstNonNull(ctx.get("guard.final.action"), ctx.get("guard.action")), 128);
        if (containsIgnoreCase(guardAction, "DEGRADE") || containsIgnoreCase(guardAction, "evidence")) {
            out.add("Guard가 evidence list로 degrade → 인용/근거 부족. 도메인 제한 완화 또는 minCitations/리랭크 정책을 조정하고, debug 헤더로 Orchestration State를 확인하세요.");
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
                if (k == null) continue;
                keys.add(String.valueOf(k));
            }
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (String k : keys) {
                Object v = m.get(k);
                if (v == null) continue;
                if (shown++ > 0) sb.append(", ");
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
                "orch.mode",
                "orch.reason",
                "orch.strike",
                "orch.compression",
                "orch.bypass",
                "guard.final.action",
                "aux.keywordSelection",
                "aux.keywordSelection.degraded.reason",
                "aux.queryTransformer",
                "web.failsoft.starvationFallback",
                "web.failsoft.starvationFallback.used",
                "web.failsoft.starvationFallback.poolSafeEmpty",
                "cacheOnly.merged.count",
                "web.failsoft.stageCountsSelectedFromOut",
                "outCount",
                "web.hybrid.braveJoin.policy",
                "web.hybrid.braveJoin.mode",
                "web.hybrid.braveJoin.deadlineCapMs",
                "web.hybrid.braveJoin.soft",
                "web.hybrid.braveJoin.softMs",
                "web.hardDown",
                "web.await.skipped.count",
                "aux.keywordSelection.forceMinMust.applied",
                "aux.keywordSelection.forceMinMust.reason",
                "aux.keywordSelection.forceMinMust.before",
                "aux.keywordSelection.forceMinMust.after"
        );
    }

    private static Object firstNonNull(Object... vs) {
        if (vs == null) return null;
        for (Object v : vs) {
            if (v != null) return v;
        }
        return null;
    }

    private static String getString(Map<String, Object> ctx, String key) {
        if (ctx == null || key == null) return null;
        Object v = ctx.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s;
    }

    private static String firstNonBlank(String... ss) {
        if (ss == null) return null;
        for (String s : ss) {
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        if (s == null || needle == null || needle.isBlank()) return false;
        return s.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String safeInline(Object v, int maxLen) {
        if (v == null) return "";
        String s = String.valueOf(v);
        s = SafeRedactor.redact(s);
        s = s.replaceAll("\\s+", " ").trim();
        if (maxLen > 0 && s.length() > maxLen) {
            s = s.substring(0, maxLen) + "…";
        }
        return s;
    }

    private static String escapeMd(String s) {
        if (s == null) return "";
        // Avoid breaking backticks/code blocks.
        return s.replace("`", "'");
    }
}
