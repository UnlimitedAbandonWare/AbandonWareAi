
package com.example.lms.risk;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import java.util.regex.Pattern;




@Component
@ConditionalOnProperty(name="risk.rdi.enabled", havingValue = "true", matchIfMissing = true)
public class RiskScorer {
    private static final Pattern TOXIC = Pattern.compile("(hate|violence|abuse|자살|혐오|폭력)", Pattern.CASE_INSENSITIVE);

    public RiskDecisionIndex score(String query, String[] sourcesTier) {
        double tox = TOXIC.matcher(query==null?"":query).find()? 0.7: 0.0;
        // Lower tier sources increase risk (e.g., social media)
        double trustPenalty = 0.0;
        if (sourcesTier != null) {
            for (String t: sourcesTier) {
                if (t==null) continue;
                if (t.contains("gov")||t.contains("edu")||t.contains("news.tier1")) trustPenalty += 0.0;
                else trustPenalty += 0.1;
            }
        }
        double rdi = Math.max(0, Math.min(1, tox + trustPenalty));
        return new RiskDecisionIndex(rdi);
    }

    public int shrinkTopK(int baseK, RiskDecisionIndex idx, int minK) {
        double g = 1.0 - 0.6*idx.rdi; // shrink up to 60%
        int k = (int)Math.round(baseK * g);
        return Math.max(minK, k);
    }
}