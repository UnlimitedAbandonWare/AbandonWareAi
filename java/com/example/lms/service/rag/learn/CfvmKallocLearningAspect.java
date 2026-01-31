package com.example.lms.service.rag.learn;

import ai.abandonware.nova.orch.failpattern.FailurePatternMatch;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DynamicRetrievalHandlerChain 단위에서 "약한 피드백"을 수집하여
 * CFVM 밴딧 스토어를 업데이트한다.
 *
 * <p>
 * 주의: 이 피드백은 정답률 기반이 아니라,
 * (문서 수 증가, latency, failure-pattern 발생) 같은 간접 신호만 사용한다.
 * 따라서 보상 모델은 보수적으로 두고, 나중에 AnswerQualityEvaluator/사용자 평가 등
 * 강한 reward로 교체할 수 있게 구조만 만든다.
 */
@Aspect
@Component
@Order(60)
public class CfvmKallocLearningAspect {

    private static final Logger log = LoggerFactory.getLogger(CfvmKallocLearningAspect.class);

    private final CfvmKAllocationTuner tuner;
    private final CfvmKallocLearningProperties props;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FailurePatternOrchestrator failurePatterns;

    public CfvmKallocLearningAspect(CfvmKAllocationTuner tuner,
            CfvmKallocLearningProperties props) {
        this.tuner = tuner;
        this.props = props;
    }

    @Around("execution(* com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain.handle(..))")
    public Object aroundHandle(ProceedingJoinPoint pjp) throws Throwable {
        if (props == null || !props.isEnabled()) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();
        if (args == null || args.length < 2) {
            return pjp.proceed();
        }

        Query q = null;
        @SuppressWarnings("unchecked")
        List<Content> acc = (args[1] instanceof List<?> l) ? (List<Content>) l : null;
        if (args[0] instanceof Query qq) {
            q = qq;
        }

        if (acc == null) {
            return pjp.proceed();
        }

        int before = acc.size();
        long start = System.currentTimeMillis();
        String sid = null;
        try {
            sid = MDC.get("sid");
        } catch (Exception ignored) {
        }

        try {
            return pjp.proceed();
        } finally {
            long elapsed = Math.max(0L, System.currentTimeMillis() - start);
            int after = acc.size();
            int deltaDocs = after - before;

            Object keyObj = TraceStore.get("cfvm.kalloc.key");
            Object armObj = TraceStore.get("cfvm.kalloc.arm");
            // Only proceed with bandit update if both key and arm are present
            if (keyObj != null && armObj != null) {
                String tileKey = String.valueOf(keyObj);
                String arm = String.valueOf(armObj);

                int failures = 0;
                if (failurePatterns != null) {
                    try {
                        List<FailurePatternMatch> recent = failurePatterns.recentMatchesSince(start, sid);
                        failures = (recent == null ? 0 : recent.size());
                    } catch (Exception ignored) {
                        failures = 0;
                    }
                }

                double reward = computeReward(deltaDocs, elapsed, failures);
                TraceStore.put("cfvm.kalloc.reward", reward);

                // update bandit
                try {
                    tuner.feedback(tileKey, arm, reward);
                } catch (Exception e) {
                    log.debug("[CFVM] feedback failed-soft: {}", e.toString());
                }
            }
        }
    }

    private double computeReward(int deltaDocs, long elapsedMs, int failureCount) {
        // docScore: tanh(delta/scale) -> (0..1)
        double scale = Math.max(1e-6, props.getDocRewardScale());
        double docScore = Math.tanh((double) deltaDocs / scale);

        // latencyPenalty: (elapsed/budget) clipped to 0..1
        long budget = Math.max(1L, props.getLatencyBudgetMs());
        double latencyPenalty = Math.min(1.0, (double) elapsedMs / (double) budget);

        // failurePenalty: any failure => 1.0 (coarse)
        double failurePenalty = failureCount > 0 ? 1.0 : 0.0;

        double r = docScore
                - props.getLatencyPenaltyWeight() * latencyPenalty
                - props.getFailurePenaltyWeight() * failurePenalty;

        // Evidence-quality features (0..1) captured into TraceStore by the retrieval chain
        // and/or ChatWorkflow (for needle contribution).
        double authorityAvg = clamp01(readDouble("cfvm.sig.authorityAvg", 0.0));
        double coverageScore = clamp01(readDouble("cfvm.sig.coverage", 0.0));
        double duplicateRatio = clamp01(readDouble("cfvm.sig.dupRatio", 0.0));
        double needleContribution = clamp01(readDouble("probe.needle.contribution.ratio", 0.0));

        r += props.getAuthorityWeight() * authorityAvg;
        r += props.getCoverageWeight() * coverageScore;
        r -= props.getDuplicatePenaltyWeight() * duplicateRatio;
        r += props.getNeedleContributionWeight() * needleContribution;

        // clamp
        r = Math.max(props.getMinReward(), Math.min(props.getMaxReward(), r));
        return r;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double readDouble(String key, double def) {
        Object v = TraceStore.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return def;
            return Double.parseDouble(s);
        } catch (Exception ignore) {
            return def;
        }
    }
}
