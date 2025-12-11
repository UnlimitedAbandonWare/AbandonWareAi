

// src/main/java/com/example/lms/service/DefaultQueryTransformer.java
package com.example.lms.service;

import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.*;




/**
 * “기본” QueryTransformer.
 * ─ 프로젝트 전역에서 사용하는 오타‧alias‧마켓플레이스 키워드 확장을 담당한다.
 *
 * ✱ 핵심: 부모(QueryTransformer)는 기본 생성자가 없으므로
 * 반드시 super(llm /* ... *&#47;) 를 호출해야 한다.
 */
@Component
public class DefaultQueryTransformer extends QueryTransformer {

    private static final Map<String, String> ALIAS = Map.of(
            "폴드7", "갤럭시 Z 폴드 7",
            "폴드6", "갤럭시 Z 폴드 6"
    );

    // ✅ 수정: OpenAiChatModel -> ChatModel 인터페이스로 변경하고, @Qualifier("miniModel") 추가
    public DefaultQueryTransformer(@Qualifier("miniModel") ChatModel llm) {
        super(llm);
    }

    @Override
    public List<String> transform(String context, String query) {
        if (query == null || query.isBlank()) return List.of();

        // 별칭 치환 후 부모 클래스의 변환 로직 호출
        String normalized = ALIAS.getOrDefault(query.trim(), query.trim());
        List<String> out = new ArrayList<>(super.transform(context, normalized));

        // 의료/공공 질문이 아닐 경우, 중고 장터 키워드 추가
        if (!NaverSearchService.MEDICAL_OR_OFFICIAL_PATTERN.matcher(normalized).find()) {
            if (!NaverSearchService.containsJoongna(normalized)) {
                out.add(normalized + " 중고나라");
            }
            if (!NaverSearchService.containsBunjang(normalized)) {
                out.add(normalized + " 번개장터");
            }
        }

        // 중복 제거 후 반환
        return out.stream().distinct().toList();
    }
}