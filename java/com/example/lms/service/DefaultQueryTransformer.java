package com.example.lms.service;

import com.example.lms.transform.QueryTransformer;      // 부모 클래스
import dev.langchain4j.model.openai.OpenAiChatModel;    // LLM 주입
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * “기본” QueryTransformer.
 *  ─ 프로젝트 전역에서 사용하는 오타‧alias‧마켓플레이스 키워드 확장을 담당한다.
 *
 *  ✱ 핵심: 부모(QueryTransformer)는 기본 생성자가 없으므로
 *         반드시 super(llm …) 를 호출해야 한다.
 */
@Component   // → Spring Bean 자동 등록
public class DefaultQueryTransformer extends QueryTransformer {

    /* ──────────────── 1. 별칭 테이블 ──────────────── */
    private static final Map<String, String> ALIAS = Map.of(
            "폴드7", "갤럭시 Z 폴드 7",
            "폴드6", "갤럭시 Z 폴드 6"
    );

    /* ──────────────── 2. 생성자 (⚠️ super 호출) ───── */
    public DefaultQueryTransformer(OpenAiChatModel llm) {
        // ▸ 사전과 HintExtractor 가 필요 없다면 빈 Map / null 로 넘기면 됨
        super(llm);                 // ← 컴파일-에러의 원인 해결!
    }

    /* ──────────────── 3. 변환 로직 오버라이드 ─────── */
    @Override
    public List<String> transform(String context, String query) {

        if (query == null || query.isBlank()) {
            return List.of();
        }

        /* 3-1.  별칭 치환 + 부모 로직 재사용 */
        String normalized = ALIAS.getOrDefault(query.trim(), query.trim());
        List<String> out = new ArrayList<>(super.transform(context, normalized));

        /* 3-2.  의료/공공 질문이 아니면 중고 키워드 자동 확장 */
        if (!NaverSearchService.MEDICAL_OR_OFFICIAL_PATTERN.matcher(normalized).find()) {

            if (!NaverSearchService.containsJoongna(normalized)) {
                out.add(normalized + " 중고나라");
            }
            if (!NaverSearchService.containsBunjang(normalized)) {
                out.add(normalized + " 번개장터");
            }
        }

        return out.stream().distinct().toList();
    }
}
