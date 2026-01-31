package com.example.lms.service.guard;

import java.util.Locale;

/**
 * "정보 없음 / 증거 부족" 류의 회피성 답변을 감지하기 위한 공통 패턴 유틸.
 *
 * - ChatService.isDefinitiveFailure(...)
 * - EvidenceAwareGuard.looksNoEvidenceTemplate(...)
 * 에서 재사용 가능.
 */
public final class InfoFailurePatterns {

    /**
     * 회피성 답변을 감지할 핵심 마커 문자열들.
     * PromptBuilder의 HALLUCINATION OF IGNORANCE GUARD와 의미적으로 동일하게 유지.
     */
    public static final String[] MARKERS = {
            "충분한 증거를 찾지 못했습니다",
            "정보가 부족하여",
            "자료가 부족하여",
            "정보 없음",
            "정보를 찾을 수 없",
            "확인되지 않",
            "insufficient evidence",
            "no relevant information",
            "unable to find",
            "죄송합니다",
            "도움을 드리기 어렵습니다"
            , "공식 정보 없"
            , "공식 발표 없"
            , "아직 발표되지"
            , "까지만 출시"
            , "정보가 없"
            , "확인된 정보가 없"
            , "공개된 정보가 없"
    };

    private InfoFailurePatterns() {
    }

    /**
     * 텍스트가 "정보 없음" 류의 회피성 답변인지 강하게 판정.
     *
     * - 마커가 전혀 없으면 false.
     * - 마커가 앞부분(처음 ~60자)에 나오고, 전체 길이가 짧으면 true.
     */
    public static boolean looksLikeFailure(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);

        boolean hasInsufficientInfoMarker = false;
        Integer earliestIndex = null;

        for (String m : MARKERS) {
            String ml = m.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf(ml);
            if (idx >= 0) {
                hasInsufficientInfoMarker = true;
                if (earliestIndex == null || idx < earliestIndex) {
                    earliestIndex = idx;
                }
            }
        }

        if (!hasInsufficientInfoMarker) {
            return false;
        }

        boolean hasSubstantialContent = normalized.length() > 80;
        boolean markerAtFront = (earliestIndex != null && earliestIndex < 60);

        return !hasSubstantialContent || markerAtFront;
    }
}
