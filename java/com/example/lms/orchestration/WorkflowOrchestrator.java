package com.example.lms.orchestration;

import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.Locale;

/**
 * Planner Nexus - Workflow Orchestrator
 *
 * 플랜 ID가 미지정일 때 자동으로 적합한 플랜을 선택합니다.
 */
@Component
@RequiredArgsConstructor
public class WorkflowOrchestrator {

    private final PlanHintApplier planHintApplier;

    @Value("${plans.auto-select.enabled:true}")
    private boolean enabled;

    @Value("${plans.auto-select.default:safe_autorun.v1}")
    private String defaultPlanId;

    @Value("${plans.auto-select.safe:safe_autorun.v1}")
    private String safePlanId;

    @Value("${plans.auto-select.creative:brave.v1}")
    private String creativePlanId;

    @Value("${plans.auto-select.recency:recency_first.v1}")
    private String recencyPlanId;

    @Value("${plans.auto-select.entity:kg_first.v1}")
    private String entityPlanId;

    public String ensurePlanSelected(GuardContext ctx, AnswerMode answerMode,
                                    QueryDomain domain, String userQuery) {
        if (ctx == null) return null;
        if (ctx.getPlanId() != null && !ctx.getPlanId().isBlank()) {
            return ctx.getPlanId();
        }

        String selected = enabled
                ? selectPlan(ctx, answerMode, domain, userQuery)
                : defaultPlanId;

        if (selected == null || selected.isBlank()) {
            selected = defaultPlanId;
        }

        // PlanHintApplier로 정규화
        try {
            selected = planHintApplier.load(selected).planId();
        } catch (Exception ignore) {
        }

        ctx.setPlanId(selected);
        TraceStore.put("plan.auto", selected);
        return selected;
    }

    private String selectPlan(GuardContext ctx, AnswerMode answerMode,
                              QueryDomain domain, String userQuery) {
        // Priority 1) SENSITIVE → safe
        if (domain == QueryDomain.SENSITIVE) {
            return safePlanId;
        }

        // ✅ MoE YELLOW: GAME/SUBCULTURE → creative/community plan (avoid official-only lock)
        if (domain == QueryDomain.GAME || domain == QueryDomain.SUBCULTURE) {
            TraceStore.put("moe.color", "YELLOW");
            return creativePlanId;
        }

        // Priority 2) explicit brave/creative hint (header/mode)
        if (looksBraveHint(ctx)) {
            return creativePlanId;
        }

        // Priority 3) CREATIVE mode → brave
        if (answerMode == AnswerMode.CREATIVE) {
            return creativePlanId;
        }

        // Priority 4) recency queries → recency_first
        if (looksRecency(userQuery)) {
            return recencyPlanId;
        }

        // Below: lightweight AP routing (MoE-ish)

        // ap9: cheap/fast
        if (looksCostOrFast(userQuery)) {
            return "ap9_cost_saver.v1";
        }

        // ap11: finance specialization
        if (looksFinance(userQuery)) {
            return "ap11_finance_special.v1";
        }

        // ap1: legal/government/official
        if (looksLegalOrOfficial(userQuery)) {
            return "ap1_auth_web.v1";
        }

        // ap3: code / stack traces / debugging
        if (looksCodeOrStackTrace(userQuery)) {
            return "ap3_vec_dense.v1";
        }

        // kg_first: entity-ish / graph-ish
        if (ctx.isEntityQuery()) {
            return entityPlanId;
        }

        // default
        return safePlanId;
    }

    private static boolean looksBraveHint(GuardContext ctx) {
        if (ctx == null) return false;
        String hm = ctx.getHeaderMode();
        if (hm == null || hm.isBlank()) return false;
        String lower = hm.toLowerCase(Locale.ROOT);
        return lower.contains("brave") || lower.contains("creative") || lower.equals("s2") || lower.equals("free");
    }

    private static boolean looksCostOrFast(String q) {
        if (q == null || q.isBlank()) return false;
        String lower = q.toLowerCase(Locale.ROOT);
        return lower.matches(".*(빠르게|빨리|간단히|짧게|요약|한\\s*줄|tldr|quick|fast|cheap|저렴|비용|cost).*" );
    }

    private static boolean looksFinance(String q) {
        if (q == null || q.isBlank()) return false;
        String lower = q.toLowerCase(Locale.ROOT);
        return lower.matches(".*(주식|코인|가상자산|비트코인|이더리움|btc|eth|nasdaq|s&p|etf|per|p/e|배당|환율|금리|채권|재무|실적|시가총액).*" );
    }

    private static boolean looksLegalOrOfficial(String q) {
        if (q == null || q.isBlank()) return false;
        String lower = q.toLowerCase(Locale.ROOT);
        return lower.matches(".*(법|법률|판례|소송|변호사|헌법|규정|시행령|행정|정부|공공|공식|보도자료|국세청|고시|법령).*" );
    }

    private static boolean looksCodeOrStackTrace(String q) {
        if (q == null || q.isBlank()) return false;
        String lower = q.toLowerCase(Locale.ROOT);
        if (lower.contains("```")) return true;
        if (lower.contains("exception") || lower.contains("traceback") || lower.contains("stack trace")
                || lower.contains("nullpointerexception") || lower.contains("illegalargumentexception")
                || lower.contains("segmentation fault")) {
            return true;
        }
        // Common Java stack trace shape: "at com.foo.Bar(Baz.java:123)"
        if (lower.contains("at ") && lower.contains("(") && lower.contains(")") && lower.contains(":")) {
            return true;
        }
        // Common Python trace shape
        if (lower.contains("traceback") || (lower.contains("file \"") && lower.contains("line "))) {
            return true;
        }
        return false;
    }

    private static boolean looksRecency(String q) {
        if (q == null || q.isBlank()) return false;
        String lower = q.toLowerCase(Locale.ROOT);
        int year = Year.now().getValue();
        if (lower.contains(String.valueOf(year)) || lower.contains(String.valueOf(year + 1))) {
            return true;
        }
        return lower.matches(".*(최신|최근|업데이트|패치|출시|발표|뉴스|근황|현재|지금|오늘|latest|recent|update|release|news).*" );
    }
}
