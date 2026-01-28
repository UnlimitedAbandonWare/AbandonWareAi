package com.example.lms.service.llm;

import com.example.lms.infra.resilience.FriendShieldPatternDetector;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class LangChain4jLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmClient.class);

    /**
     * Utility LLM: keep it fast and fail-soft.
     */
    @Qualifier("fastChatModel")
    private final ChatModel chatModel;

    public LangChain4jLlmClient(@Qualifier("fastChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @Override
    public String complete(String prompt) {
        return completeWithKey(NightmareKeys.FAST_LLM_COMPLETE, prompt);
    }

    @Override
    public String completeWithKey(String breakerKey, String prompt) {
        String key = (breakerKey == null || breakerKey.isBlank())
                ? NightmareKeys.FAST_LLM_COMPLETE
                : breakerKey;

        // ✅ 오케스트레이션 접목: 단계별 key로 브레이커 상태를 공유해야 상위가 제대로 degrade 한다.
        if (nightmareBreaker != null) {
            return nightmareBreaker.execute(
                    key,
                    prompt,
                    () -> {
                        var res = chatModel.chat(UserMessage.from(prompt));
                        if (res == null || res.aiMessage() == null) return "";
                        var ai = res.aiMessage();
                        return ai.text() == null ? "" : ai.text();
                    },
                    FriendShieldPatternDetector::looksLikeSilentFailure,
                    () -> ""
            );
        }

        try {
            var res = chatModel.chat(UserMessage.from(prompt));
            if (res == null || res.aiMessage() == null) return "";
            var ai = res.aiMessage();
            return ai.text() == null ? "" : ai.text();
        } catch (Exception e) {
            log.warn("LLM call failed: {}", e.toString());
            return "";
        }
    }
}