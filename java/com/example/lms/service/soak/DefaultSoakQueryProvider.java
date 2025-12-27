package com.example.lms.service.soak;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.ArrayList;
import java.util.List;

/** Public default implementation so it can be wired from @Configuration. */
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DefaultSoakQueryProvider implements SoakQueryProvider {
    @Override
    public List<String> queries(String topic) {
        String t = (topic == null || topic.isBlank()) ? "all" : topic.trim();

        switch (t) {
            case "naver-brave-fixed10":
            case "brave-naver-fixed10":
            case "naver-bing-fixed10":
                // Fixed set used for quick soak smoke tests.
                // Mix of employment / dev / vector topics to catch cross-domain contamination.
                return List.of(
                        "메이크인 위탁기관 청년취업지원금",
                        "국민취업지원제도 위탁기관 수당 지급 기준",
                        "고용24 취업지원금 지급일",
                        "내일배움카드 훈련장려금 조건",
                        "서울 청년수당 신청 기간",
                        "실업급여 1차 실업인정 온라인",
                        "Brave Search API 422 count 파라미터",
                        "pinecone index namespace metadata filter",
                        "RAG에서 evidence(근거) 인용 강제하는 프롬프트 패턴",
                        "원신 마비카 스커크 관계"
                );
            case "employment":
            case "makein":
                return List.of(
                        "메이크인 위탁기관이 하는 일은 뭐야?",
                        "국민취업지원제도(국취제) 1유형 2유형 차이",
                        "취업지원금 지급 조건과 지급 시점",
                        "위탁기관과 고용센터 역할 차이",
                        "취업활동계획(IAP) 변경 절차",
                        "취업지원금 지급 누락/지연 시 조치 방법",
                        "취업성공수당 지급 요건과 시점",
                        "청년취업지원금 신청 서류 체크리스트",
                        "고용센터 민원/이의신청 절차",
                        "위탁기관 상담 시 주의사항"
                );
            case "genshin":
                return List.of(
                        "원신 성유물 파밍 루트",
                        "원신 풀 원소 파티 추천"
                );
            case "default":
                return List.of(
                        "RAG 파이프라인이 뭐야?",
                        "크로스 인코더 장단점"
                );
            default:
                List<String> all = new ArrayList<>();
                all.addAll(queries("default"));
                all.addAll(queries("genshin"));
                return all;
        }
    }
}