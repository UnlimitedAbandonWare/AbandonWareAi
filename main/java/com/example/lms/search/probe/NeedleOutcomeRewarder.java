package com.example.lms.search.probe;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Computes and logs reward signals for needle probe outcomes.
 * Used for reinforcement learning and tuning probe trigger thresholds.
 */
@Component
public class NeedleOutcomeRewarder {

    private static final Logger log = LoggerFactory.getLogger(NeedleOutcomeRewarder.class);

    @Autowired(required = false)
    private CausalProbeTriggerService causalProbeTriggerService;

    /**
     * Compute reward based on needle contribution.
     *
     * @param contribution the needle contribution metrics
     * @return reward value (positive = good, negative = wasted effort)
     */
    public double computeReward(NeedleContribution contribution) {
        if (contribution == null || !contribution.triggered()) {
            return 0.0;
        }

        double reward = 0.0;

        // Reward for effective contribution
        if (contribution.isEffective()) {
            reward += contribution.docsUsedInTopN() * 0.5;
            reward += contribution.qualityDelta() * 2.0;
        } else {
            // Penalty for triggered but ineffective
            reward = -0.2;
        }

        // Log for observability
        try {
            TraceStore.put("needle.reward.value", reward);
            TraceStore.put("needle.reward.effective", contribution.isEffective());
        } catch (Exception ignore) {
            traceSuppressed("trace.reward");
        }

        log.debug("[NeedleReward] reward={} effective={} docsUsed={} qualityDelta={}",
                reward, contribution.isEffective(),
                contribution.docsUsedInTopN(), contribution.qualityDelta());

        recordOutcome(contribution, reward);
        return reward;
    }

    /**
     * Record outcome for future learning.
     *
     * @param contribution the contribution metrics
     * @param reward       computed reward
     */
    public void recordOutcome(NeedleContribution contribution, double reward) {
        if (contribution == null)
            return;

        try {
            TraceStore.append("needle.outcomes", java.util.Map.of(
                    "triggered", contribution.triggered(),
                    "docsAdded", contribution.docsAdded(),
                    "docsUsedInTopN", contribution.docsUsedInTopN(),
                    "qualityDelta", contribution.qualityDelta(),
                    "reward", reward));
        } catch (Exception ignore) {
            traceSuppressed("trace.outcome");
        }

        projectCausalProbe();
    }

    private void projectCausalProbe() {
        try {
            if (causalProbeTriggerService != null) {
                causalProbeTriggerService.projectCurrentTrace(null, "NeedleOutcomeRewarder.recordOutcome");
            }
        } catch (Exception ignore) {
            traceSuppressed("trace.causalProbe");
        }
    }

    private static void traceSuppressed(String stage) {
        log.debug("[NeedleReward] suppressed stage={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
    }
}
