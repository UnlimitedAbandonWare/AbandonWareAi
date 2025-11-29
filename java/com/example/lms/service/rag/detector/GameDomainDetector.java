// src/main/java/com/example/lms/service/rag/detector/GameDomainDetector.java
package com.example.lms.service.rag.detector;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.Locale;
import java.util.Set;




@Component
public class GameDomainDetector {

    /**
     * 힌트 세트 정의: 도메인을 추정하기 위해 사용되는 키워드 모음입니다.
     *
     * <p>GENSHIN_HINTS: '원신' 도메인에 해당하는 대표 키워드와 캐릭터 명칭. 이 세트에
     * 포함된 단어가 질의에 존재하면 "GENSHIN" 도메인을 반환합니다.</p>
     *
     * <p>EDU_HINTS: 교육/국비지원 관련 질의에 해당하는 키워드. "학원", "국비",
     * "국비지원", "교육", "훈련" 등을 포함하며, HRD-Net(고용노동부 직업훈련정보망)
     * 과 같은 사이트의 경우도 포괄하기 위해 "hrd" 문자열도 포함합니다. 이 세트에
     * 해당하는 키워드가 발견되면 "EDUCATION" 도메인을 반환합니다.</p>
     */
    private static final Set<String> GENSHIN_HINTS = Set.of(
            "원신", "genshin", "호요버스", "hoyoverse",
            // 대표 캐릭터/명칭 일부
            "에스코피에", "escoffier", "푸리나", "furina", "다이루크", "diluc", "호두", "hutao"
    );

    // 교육/국비지원 관련 힌트: 학원, 국비, 국비지원, 교육, 훈련, HRD 등이 포함됩니다.
    private static final Set<String> EDU_HINTS = Set.of(
            "학원", "국비", "국비지원", "교육", "훈련", "hrd", "직업훈련", "취업지원", "자격증", "직업교육"
    );

    public String detect(String q) {
        if (!StringUtils.hasText(q)) {
            return "GENERAL";
        }
        // 소문자화하여 키워드 검색을 용이하게 한다.
        String s = q.toLowerCase(Locale.ROOT);

        // 교육 관련 키워드가 있으면 "EDUCATION" 도메인을 우선 반환한다. 이는
        // 국비지원·직업훈련·학원 등의 질의에 대해 벡터 기반 RAG 파이프라인으로
        // 전환하기 위함이다.
        for (String h : EDU_HINTS) {
            if (s.contains(h.toLowerCase(Locale.ROOT))) {
                return "EDUCATION";
            }
        }

        // Genshin 관련 키워드 검사. 발견 시 "GENSHIN"을 반환한다.
        for (String h : GENSHIN_HINTS) {
            if (s.contains(h.toLowerCase(Locale.ROOT))) {
                return "GENSHIN";
            }
        }
        // 그 외에는 일반 도메인으로 분류한다.
        return "GENERAL";
    }
}