package com.example.lms.service.scoring;

import com.example.lms.search.TraceStore;
import org.springframework.stereotype.Service;

@Service
public class AdaptiveScoringService {
    public void applyImplicitPositive(String domain, String subject, String partner, double weight) {
        TraceStore.put("scoring.adaptive.implicitPositive.observed", true);
    }

    public double getSynergyScore(String domain, String subject, String partner) {
        TraceStore.put("scoring.adaptive.synergyScore.fallback", true);
        return 0.0d;
    }
}
