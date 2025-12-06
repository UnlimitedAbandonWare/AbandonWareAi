package com.example.lms.prompt;

import com.example.lms.service.disambiguation.DisambiguationResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * QueryDisambiguationService 에서 사용할 프롬프트를 생성하는 빌더.
 * <p>
 * 범용 도메인(게임/교육뿐 아니라 쇼핑, 코딩, 부동산, 반려동물 등)을 모두 처리할 수 있는
 * "Universal" 프롬프트를 제공한다.
 */
@Component
public class DisambiguationPromptBuilder {

    /**
     * 레거시 호출자를 위한 래퍼 메서드. 내부적으로 {@link #buildUniversal} 을 호출한다.
     */
    public String build(String query, List<String> history) {
        return buildUniversal(query, history, null);
    }

    /**
     * 범용 도메인 분석용 프롬프트를 생성한다.
     *
     * @param query   현재 사용자 질의
     * @param history 포맷팅된 최근 대화 히스토리 (가장 오래된 순서)
     * @param seed    사전(Dictionary) 기반으로 추출된 힌트 (없으면 null)
     */
    public String buildUniversal(String query, List<String> history, DisambiguationResult seed) {
        String histTxt = (history == null || history.isEmpty())
                ? ""
                : String.join("\n", history);

        String seedHint = "";
        if (seed != null && seed.getTargetObject() != null && !seed.getTargetObject().isBlank()) {
            seedHint = "Known term hint: " + seed.getTargetObject() + "\n";
        }

        String q = (query == null) ? "" : query;

        // Java 17 텍스트 블록을 사용해 가독성을 높인다.
        return String.format("""
                You are a universal query analyzer for an AI assistant.
                You MUST handle not only games but also generic topics:
                smartphones (e.g., Galaxy Fold), pets (e.g., puppy food),
                programming (e.g., Java/Spring Boot), real estate, shopping,
                education and everyday conversation.

                Analyse the user's query (in Korean or mixed languages) and
                return ONLY a single-line JSON object that conforms to this schema:

                {
                  "ambiguousTerm": "string | null",
                  "resolvedIntent": "string | null",
                  "rewrittenQuery": "Cleaned Korean query for web/RAG search",
                  "detectedCategory": "ELECTRONICS | PET | REAL_ESTATE | DEV_TOPIC | EDUCATION | GAME | GENERAL | UNKNOWN",
                  "targetObject": "Main subject of the query",
                  "attributes": {
                    "brand": "Samsung",
                    "action": "buy",
                    "price_range": "1000000-1500000"
                  },
                  "queryIntent": "GENERAL_SEARCH | SPECIFIC_ITEM | HOW_TO | DEBUGGING | SHOPPING | CHAT",
                  "confidence": "high | medium | low",
                  "score": 0.0
                }

                Requirements:
                - Output MUST be valid JSON. Do not include any extra commentary.
                - Use the user's language (usually Korean) inside string fields.
                - When the query is about a concrete product (e.g., Galaxy Fold, puppy food),
                  set detectedCategory and queryIntent appropriately (e.g., ELECTRONICS + SHOPPING).
                - When the query is about programming errors or how-to questions,
                  set detectedCategory=DEV_TOPIC and queryIntent=DEBUGGING or HOW_TO.
                - If you are not sure, use GENERAL and GENERAL_SEARCH.

                %s
                [Conversation history, oldest→latest]
                %s

                [Current query]
                %s
                """, seedHint, histTxt, q);
    }
}
