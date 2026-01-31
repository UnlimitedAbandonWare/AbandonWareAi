package com.example.guard;

import org.junit.jupiter.api.Test;
import java.util.Set;


import static org.junit.jupiter.api.Assertions.*;

public class RiskScorerTest {
    @Test
    void weightIsReduced_whenRiskIsHigh() {
        var scorer = new RiskScorer();
        var props  = new RiskScorer.RiskProps(0.5, Set.of("루머","카더라"), 0.25, 0.25);

        var safe = new RiskScorer.Features("gov.kr", "공식 문서입니다.", false, 0.2, 0.9);
        var risky= new RiskScorer.Features("unknown.site", "이건 루머에 불과", true, 0.9, 0.2);

        double rsafe = scorer.score(safe, props);
        double rrisk = scorer.score(risky, props);

        assertTrue(rrisk > rsafe);
        assertTrue(rrisk <= 1.0 && rsafe >= 0.0);
    }
}