
        package com.example.lms.service.fallback;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartFallbackService {

    private final ObjectProvider<OpenAiService> openAiProvider;

    @Value("${fallback.enabled:true}")
    private boolean enabled;
    @Value("${fallback.model:gpt-4o-mini}")
    private String model;
    @Value("${fallback.temperature:0.2}")
    private double temperature;
    @Value("${fallback.top-p:0.7}")
    private double topP;
    @Value("${fallback.max-tokens:280}")
    private Integer maxTokens;

    /**
     * 컨텍스트가 부족하거나 최종 답변이 '정보 없음'일 때,
     * 사용자 오타/오해를 정중히 교정하고 대안을 제시하는 폴백 답변을 생성한다.
     *
     * @return null 이면 폴백 미적용
     */
    public @Nullable String maybeSuggest(String userQuery, @Nullable String joinedContext, @Nullable String verified) {
        if (!enabled) return null;

        boolean insufficient = !StringUtils.hasText(joinedContext);
        boolean noInfo = StringUtils.hasText(verified) && "정보 없음".equals(verified.trim());
        if (!insufficient && !noInfo) return null;

        FallbackHeuristics.Detection det = FallbackHeuristics.detect(userQuery);
        if (det == null) return null; // 도메인/문제어가 명확하지 않은 경우 패스

        List<String> candidates = FallbackHeuristics.suggestAlternatives(det.domain(), det.wrongTerm());

        OpenAiService openAi = openAiProvider.getIfAvailable();
        if (openAi == null) return templateFallback(det, candidates, userQuery); // 안전 템플릿

        String system = """
                너는 컨텍스트가 부족한 상황에서 사용자 의도를 정중히 바로잡는 한국어 어시스턴트다.
                - 단정하지 말고, 최대 6줄 이내로 답하라.
                - 새로운 사실을 '단정'하지 마라. 제안은 "가능한 후보"로 표현하라.
                - 톤: 친절/간결/실용.
                형식:
                1) 문제어가 무엇인지 1문장(일반적 의미) + 해당 게임과 무관함
                2) "가능한 후보:" 목록으로 2~3개 제시(짧은 이유)
                3) 마지막 줄: "정확한 이름이나 의도를 알려주시면 다시 찾아볼게요."
                """;

        String user = """
                [게임/도메인] %s
                [문제어] %s
                [원문 질의] %s
                [컨텍스트] %s
                [후보 리스트(있으면 반영, 없으면 생략적으로 표현)] %s
                """.formatted(
                det.domain(),
                det.wrongTerm(),
                userQuery == null ? "" : userQuery,
                (insufficient ? "없음/부족" : "있으나 불충분"),
                (candidates == null || candidates.isEmpty() ? "(없음)" : String.join(", ", candidates))
        );

        try {
            ChatCompletionRequest req = ChatCompletionRequest.builder()
                    .model(model)
                    .messages(List.of(
                            new ChatMessage(ChatMessageRole.SYSTEM.value(), system),
                            new ChatMessage(ChatMessageRole.USER.value(), user)
                    ))
                    .temperature(temperature)
                    .topP(topP)
                    .maxTokens(maxTokens)
                    .build();

            String out = openAi.createChatCompletion(req)
                    .getChoices().get(0).getMessage().getContent();
            return (out == null || out.isBlank()) ? templateFallback(det, candidates, userQuery) : out.trim();
        } catch (Exception e) {
            log.debug("[SmartFallback] OpenAI 호출 실패 → 템플릿 폴백 사용: {}", e.toString());
            return templateFallback(det, candidates, userQuery);
        }
    }

    /**
     * 상세 결과를 돌려주는 신규 API.
     * 기존 maybeSuggest(...) 로부터 제안 텍스트를 얻고,
     * 컨텍스트 부족/제안 존재 여부를 바탕으로 isFallback을 산출.
     */
    public FallbackResult maybeSuggestDetailed(String query,
                                               String joinedContext,
                                               String answerDraft) {
        String suggestion = maybeSuggest(query, joinedContext, answerDraft);
        boolean ctxEmpty  = !StringUtils.hasText(joinedContext) ||
                "정보 없음".equalsIgnoreCase(String.valueOf(answerDraft).trim());
        boolean isFallback = ctxEmpty || (StringUtils.hasText(suggestion));
        return new FallbackResult(suggestion, isFallback);
    }

    /** OpenAI 비가용 시 최소한의 예의바른 안내 텍스트 */
    private String templateFallback(FallbackHeuristics.Detection det, List<String> cand, String q) {
        StringBuilder sb = new StringBuilder();
        sb.append(det.wrongTerm())
                .append("는 일반적으로 게임 ‘").append(det.domain()).append("’의 캐릭터가 아니라 ")
                .append("실존 인물/개념에 더 가까워 보여요. (질의: ").append(q == null ? "" : q).append(")\n");
        if (cand != null && !cand.isEmpty()) {
            sb.append("가능한 후보: ").append(String.join(", ", cand)).append("\n");
        }
        sb.append("정확한 이름이나 의도를 알려주시면 다시 찾아볼게요.");
        return sb.toString();
    }
}