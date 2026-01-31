
        package com.example.lms.service.scoring;

import com.example.lms.domain.scoring.SynergyStat;
import com.example.lms.repository.SynergyStatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;


@Service
@RequiredArgsConstructor
public class AdaptiveScoringService {

    private final SynergyStatRepository repo;

    /** 암묵 피드백을 1로 반영할 최소 가중치(확신/일관성) */
    @Value("${scoring.implicit.threshold:0.8}")
    private double implicitThreshold;

    /**
     * 특정 조합에 대한 긍정적인 사용자 피드백을 기록합니다.
     * @param domain 도메인 (e.g., "GENSHIN")
     * @param subject 주체 (e.g., "에스코피에")
     * @param partner 파트너 (e.g., "푸리나")
     */
    public void applyPositiveFeedback(String domain, String subject, String partner) {
        upsert(domain, subject, partner, +1, 0);
    }

    /**
     * 특정 조합에 대한 부정적인 사용자 피드백을 기록합니다.
     * @param domain 도메인
     * @param subject 주체
     * @param partner 파트너
     */
    public void applyNegativeFeedback(String domain, String subject, String partner) {
        upsert(domain, subject, partner, 0, +1);
    }

    /**
     * 재랭킹 시 사용할 궁합 보너스 점수를 계산하여 반환합니다. 점수는 [-0.05, +0.10] 범위로 정규화됩니다.
     * @param domain 도메인
     * @param subject 주체
     * @param partner 파트너
     * @return 계산된 시너지 점수
     */
    public double getSynergyScore(String domain, String subject, String partner) {
        if (isBlank(subject) || isBlank(partner)) return 0.0;

        var stat = repo.findByDomainAndSubjectIgnoreCaseAndPartnerIgnoreCase(domain, subject, partner).orElse(null);
        if (stat == null) return 0.0;

        long pos = stat.getPositive();
        long neg = stat.getNegative();
        double total = pos + neg;

        // Laplace smoothing을 적용하여 데이터가 적을 때 극단적인 확률을 피합니다.
        double mean = (pos + 1.0) / (total + 2.0);
        // 점수를 [-0.5, +0.5] 범위로 중앙 정렬
        double centered = mean - 0.5;
        // 최종 점수를 [-0.05, +0.10] 범위로 조정 및 제한(clamping)
        return Math.max(-0.05, Math.min(0.10, centered * 0.3));
    }

    /**
     * 암묵적(implicit) 신호를 바탕으로 긍정 피드백을 조건부 반영한다.
     * weight ∈ [0,1]가 implicitThreshold 이상이면 + 1을 기록, 아니면 무시.
     */
    public void applyImplicitPositive(String domain, String subject, String partner, double weight) {
        if (weight >= implicitThreshold) {
            upsert(domain, subject, partner, +1, 0);
        }
    }

    private void upsert(String domain, String subject, String partner, long positiveDelta, long negativeDelta) {
        if (isBlank(domain) || isBlank(subject) || isBlank(partner)) return;

        SynergyStat stat = repo.findByDomainAndSubjectIgnoreCaseAndPartnerIgnoreCase(domain, subject, partner)
                .orElseGet(() -> {
                    SynergyStat newStat = new SynergyStat();
                    newStat.setDomain(domain);
                    newStat.setSubject(subject);
                    newStat.setPartner(partner);
                    return newStat;
                });

        stat.setPositive(stat.getPositive() + positiveDelta);
        stat.setNegative(stat.getNegative() + negativeDelta);
        stat.setUpdatedAt(Instant.now());
        repo.save(stat);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}