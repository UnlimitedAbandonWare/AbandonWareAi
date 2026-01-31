package com.example.lms.service.rag.calib;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * [시선1+2 통합] RAG 문서 점수에 시간 가중치 적용기.
 *
 * <ul>
 *     <li>최신 30일 이내: 1.8x 부스트</li>
 *     <li>6개월 이내: 1.2x 부스트</li>
 *     <li>1년 지난 루머: 0.35x 패널티</li>
 * </ul>
 *
 * <p>문서 랭킹 단계에서 baseScore에 calibrate(...) 를 적용해 사용한다.</p>
 */
@Component
public class RecencyBoostCalibrator {

    private static final String[] RUMOR_KEYWORDS = {
            "rumor", "leak", "expected", "예상", "루머", "추정", "전망", "가능성", "유출"
    };

    /**
     * 주어진 문서 점수에 시간 가중치를 적용한다.
     *
     * @param baseScore 원래 점수
     * @param docDate   문서 날짜 (null이면 수정 없이 그대로 반환)
     * @param docText   문서 텍스트 (루머 여부 탐지용)
     * @return 보정된 점수
     */
    public double calibrate(double baseScore, LocalDate docDate, String docText) {
        if (docDate == null) {
            return baseScore;
        }

        long daysGap = ChronoUnit.DAYS.between(docDate, LocalDate.now());
        double score = baseScore;

        // 최신 문서 강력 부스트 (시선2 스타일: 1.5 → 1.8)
        if (daysGap >= 0 && daysGap <= 30) {
            score *= 1.8;
        } else if (daysGap > 30 && daysGap <= 180) {
            score *= 1.2;
        }

        // 1년 지난 루머/예상 문서는 대폭 패널티 (시선2: 0.4 → 0.35)
        if (daysGap > 365 && isRumorLike(docText)) {
            score *= 0.35;
        }

        return score;
    }

    private boolean isRumorLike(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : RUMOR_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
