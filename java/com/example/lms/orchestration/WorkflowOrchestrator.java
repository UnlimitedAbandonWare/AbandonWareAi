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
        // 1) SENSITIVE 도메인 → 무조건 safe
        if (domain == QueryDomain.SENSITIVE) {
            return "safe.v1";
        }

        // 2) CREATIVE 모드 → brave
        if (answerMode == AnswerMode.CREATIVE) {
            return creativePlanId;
        }

        // 3) 최신성 쿼리 → recency
        if (looksRecency(userQuery)) {
            return recencyPlanId;
        }

        // 4) 엔티티 쿼리 → kg_first
        if (ctx.isEntityQuery()) {
            return entityPlanId;
        }

        return safePlanId;
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
