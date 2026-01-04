package com.example.lms.search.probe;

import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Computes and logs reward signals for needle probe outcomes.
 * Used for reinforcement learning and tuning probe trigger thresholds.
 */
@Component
public class NeedleOutcomeRewarder {

    private static final Logger log = LoggerFactory.getLogger(NeedleOutcomeRewarder.class);

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
        }

        log.debug("[NeedleReward] reward={} effective={} docsUsed={} qualityDelta={}",
                reward, contribution.isEffective(),
                contribution.docsUsedInTopN(), contribution.qualityDelta());

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
        }
    }
}
