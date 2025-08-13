// src/main/java/com/example/lms/service/scoring/AdaptiveScoringService.java
package com.example.lms.service.scoring;

import com.example.lms.domain.scoring.SynergyStat;
import com.example.lms.repository.SynergyStatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AdaptiveScoringService {

    private final SynergyStatRepository repo;

    /** 피드백 반영(긍정) */
    public void applyPositiveFeedback(String domain, String subject, String partner) {
        upsert(domain, subject, partner, +1, 0);
    }

    /** 피드백 반영(부정) */
    public void applyNegativeFeedback(String domain, String subject, String partner) {
        upsert(domain, subject, partner, 0, +1);
    }

    /** 재랭킹용 궁합 보너스: [-0.05, +0.10] 범위 */
    public double getSynergyScore(String domain, String subject, String partner) {
        if (isBlank(subject) || isBlank(partner)) return 0.0;
        var stat = repo.findByDomainAndSubjectIgnoreCaseAndPartnerIgnoreCase(domain, subject, partner).orElse(null);
        if (stat == null) return 0.0;
        long pos = stat.getPositive(), neg = stat.getNegative();
        double total = pos + neg;
        double mean = (pos + 1.0) / (total + 2.0);   // Laplace smoothing
        double centered = mean - 0.5;                // [-0.5, +0.5]
        return Math.max(-0.05, Math.min(0.10, centered * 0.3)); // scale & clamp
    }

    private void upsert(String domain, String subject, String partner, long dp, long dn) {
        if (isBlank(subject) || isBlank(partner)) return;
        var stat = repo.findByDomainAndSubjectIgnoreCaseAndPartnerIgnoreCase(domain, subject, partner)
                .orElseGet(() -> {
                    var s = new SynergyStat();
                    s.setDomain(domain); s.setSubject(subject); s.setPartner(partner);
                    return s;
                });
        stat.setPositive(stat.getPositive() + dp);
        stat.setNegative(stat.getNegative() + dn);
        stat.setUpdatedAt(Instant.now());
        repo.save(stat);
    }

    private static boolean isBlank(String s){ return s == null || s.isBlank(); }
}
