package com.example.lms.artplate;

import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.Random;



/**
 * Stub evolver: mutate a few knobs and return a candidate plate.
 * Real impl should log a ScoreCard and run progressive rollout (5%→15%→50%).
 */
@Component
public class ArtPlateEvolver {

    private final Random rnd = new Random();

    public enum PlateFailureBucket { NO_EVIDENCE, LOW_AUTHORITY, TIMEOUT, CONTRADICTION }

    public Optional<ArtPlateSpec> propose(PlateFailureBucket bucket, ArtPlateSpec base) {
        int webTopK = clamp(base.webTopK() + (rnd.nextBoolean() ? 2 : -2), 1, 16);
        int vecTopK = clamp(base.vecTopK() + (rnd.nextBoolean() ? 2 : -2), 1, 32);
        int webBudget = clamp(base.webBudgetMs() + (bucket == PlateFailureBucket.TIMEOUT ? 500 : 250), 300, 5000);
        int vecBudget = clamp(base.vecBudgetMs() + 250, 300, 5000);
        boolean crossEnc = true;

        ArtPlateSpec mutated = new ArtPlateSpec(
            base.id() + "_M" + rnd.nextInt(1000), base.intent(),
            webTopK, vecTopK, base.allowMemory(), base.kgOn(),
            webBudget, vecBudget,
            base.domainAllow(), Math.max(0.10, base.noveltyFloor()), Math.max(0.40, base.authorityFloor()),
            base.includeHistory(), base.includeDraft(), base.includePrevAnswer(),
            base.modelCandidates(),
            crossEnc, Math.max(2, base.minEvidence()), Math.max(1, base.minDistinctSources()),
            base.wAuthority(), base.wNovelty(), base.wFd(), base.wMatch()
        );
        return Optional.of(mutated);
    }

    public void abTest(ArtPlateSpec candidate) {
        // Hook to traffic controller & scorecard sink.
        // Intentionally empty here.
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}