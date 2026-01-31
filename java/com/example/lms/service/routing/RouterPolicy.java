package com.example.lms.service.routing;

import com.example.lms.config.MoeRoutingProps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;



/**
 * Encapsulates the mixture-of-experts escalation logic.  The router
 * determines whether a request should be promoted from the default model
 * to the high-tier model based on a set of heuristics provided via
 * {@link RouteSignal}.  These heuristics include the maximum token
 * budget, the query complexity, the uncertainty of the answer and a
 * fused evidence score.  Clients can tune the thresholds via the
 * {@code router.moe.*} and {@code router.*} properties in
 * {@code application.yml}.
 */
@Component
public class RouterPolicy {

    private final MoeRoutingProps props;

    /**
     * Maximum token threshold used for promotion.  When the requested max
     * tokens exceed this value the router will promote to the high-tier
     * model.
     */
    @Value("${router.moe.tokens-threshold:280}")
    private int tokensThreshold;

    /**
     * Complexity threshold for MOE promotion.  Complexity scores in the
     * range [0,1] above this value trigger an upgrade.
     */
    // Complexity threshold is retained but unused in the simplified promotion
    // logic.  It can still be configured for future heuristics.
    @Value("${router.moe.complexity-threshold:0.55}")
    private double complexityThreshold;

    /**
     * Uncertainty threshold for MOE promotion.  Uncertainty scores in the
     * range [0,1] above this value trigger an upgrade.
     */
    @Value("${router.moe.uncertainty-threshold:0.35}")
    private double uncertaintyThreshold;

    /**
     * Evidence mismatch threshold.  When the fused evidence score (theta)
     * exceeds this value the router will promote to the high-tier model.
     */
    // Use the unified web evidence threshold property rather than the
    // legacy evidence-mismatch threshold.  The name web-evidence-threshold
    // aligns with MoeRoutingProps and avoids confusion with evidence mismatch.
    @Value("${router.moe.web-evidence-threshold:0.55}")
    private double webEvidenceThreshold;

    /**
     * Flag controlling promotion when the underlying model enforces a rigid
     * temperature.  When true, any request for a non-default temperature
     * results in immediate escalation.  When false, the router respects the
     * chosen temperature and only escalates based on the other heuristics.
     */
    @Value("${router.moe.escalate-on-rigid-temp:true}")
    private boolean elevateOnRigidTemp;

    /**
     * Global upgrade threshold controlling the guard across all heuristics.
     * When the difference between the evidence score and this threshold
     * exceeds {@code margin} the router will promote.  This value is
     * configured via {@code router.threshold}.
     */
    @Value("${router.threshold:0.62}")
    private double upgradeThreshold;

    /**
     * Hysteresis margin used with {@code upgradeThreshold}.  Requests whose
     * evidence score falls within {@code threshold ± margin} may remain on
     * the default model to avoid oscillation.  This parameter is bound from
     * {@code router.margin}.
     */
    @Value("${router.margin:0.08}")
    private double margin;

    public RouterPolicy(MoeRoutingProps props) {
        this.props = props;
    }

    /**
     * Determine whether a request should be escalated to the high-tier MOE
     * model.  A promotion occurs when any of the heuristic fields exceed
     * their respective thresholds or when the preferred model is
     * {@code QUALITY}.  The token threshold is applied to the requested
     * maximum tokens; complexity and uncertainty thresholds are applied to
     * the respective scores; and the evidence mismatch threshold is applied
     * to the fused evidence score.  The global upgrade threshold and margin
     * provide a secondary check on the evidence score.
     *
     * @param s the routing signal summarising heuristics for the current
     *          request
     * @return {@code true} when the request should be promoted; otherwise
     *         {@code false}
     */
    public boolean shouldPromote(RouteSignal s) {
        if (s == null) {
            return false;
        }
        // Check per-metric thresholds.  Promotion occurs when any of the
        // individual heuristics exceed their respective thresholds.  Note
        // that complexity is no longer used directly for promotion; the
        // combined evidence/uncertainty heuristics provide a more robust
        // signal.  Complexity remains tunable via the property but is
        // ignored here to simplify routing.
        if (s == null) {
            return false;
        }
        if (s.maxTokens() >= tokensThreshold) {
            return true;
        }
        if (s.uncertainty() >= uncertaintyThreshold) {
            return true;
        }
        if (s.theta() >= webEvidenceThreshold) {
            return true;
        }
        // Global evidence threshold with hysteresis remains in place.  When the
        // evidence score exceeds the upgrade threshold plus margin the
        // request is promoted.  This acts as a secondary safeguard and
        // preserves backwards compatibility with existing configurations.
        if (s.theta() >= upgradeThreshold + margin) {
            return true;
        }
        // Escalate if the preferred model is QUALITY
        if (s.preferred() != null && "QUALITY".equalsIgnoreCase(s.preferred().name())) {
            return true;
        }
        return false;
    }

    public boolean isElevateOnRigidTemp() {
        return elevateOnRigidTemp;
    }
}