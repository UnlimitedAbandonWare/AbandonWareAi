// src/main/java/com/example/lms/model/ModelRouter.java
package com.example.lms.model;

import com.example.lms.llm.DynamicChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class ModelRouter {

    /** 상위 모델로 라우팅할 의도 집합 */
    private static final Set<String> HIGH_TIER_INTENTS = Set.of("PAIRING", "RECOMMENDATION");

    /** 선택적으로 주입될 수 있는 동적 팩토리 */
    @Nullable private final DynamicChatModelFactory factory;

    /** 선택적으로 주입될 수 있는 사전 구성 모델(있으면 최우선 사용) */
    @Nullable private final ChatModel utility; // 기본
    @Nullable private final ChatModel moe;     // 상위

    /** 동적 생성 시 사용할 모델명(프로퍼티로 설정, 기본값 포함) */
    private final String moeModelName;
    private final String baseModelName;

    /** 동적 생성 캐시 */
    private final AtomicReference<ChatModel> cachedMoe  = new AtomicReference<>();
    private final AtomicReference<ChatModel> cachedBase = new AtomicReference<>();

    public ModelRouter(
            @Nullable DynamicChatModelFactory factory,
            @Nullable @Qualifier("utilityChatModel") ChatModel utility,
            @Nullable @Qualifier("moeChatModel") ChatModel moe,
            @Value("${openai.model.moe:gpt-4o}") String moeModelName,
            @Value("${langchain4j.openai.chat-model.model-name:gpt-4o-mini}") String baseModelName
    ) {
        this.factory = factory;
        this.utility = utility;
        this.moe = moe;
        this.moeModelName = moeModelName;
        this.baseModelName = baseModelName;
    }

    /** 의도 기반 모델 라우팅 */
    public ChatModel route(@Nullable String intent) {
        boolean highTier = intent != null && HIGH_TIER_INTENTS.contains(intent.toUpperCase());
        return highTier ? resolveMoe() : resolveBase();
    }

    /** 상위(MOE) 모델 해석 */
    private ChatModel resolveMoe() {
        if (moe != null) return moe;                       // 주입 우선
        ChatModel cached = cachedMoe.get();
        if (cached != null) return cached;                 // 캐시 사용
        ensureFactory();                                   // 없으면 동적 생성
        return cachedMoe.updateAndGet(existing ->
                existing != null ? existing
                        : factory.lcWithPolicy("PAIRING", moeModelName, 0.2, 1.0, null)
        );
    }

    /** 기본(utility) 모델 해석 */
    private ChatModel resolveBase() {
        if (utility != null) return utility;               // 주입 우선
        ChatModel cached = cachedBase.get();
        if (cached != null) return cached;                 // 캐시 사용
        ensureFactory();                                   // 없으면 동적 생성
        return cachedBase.updateAndGet(existing ->
                existing != null ? existing
                        : factory.lc(baseModelName, 0.7, 1.0, null)
        );
    }

    private void ensureFactory() {
        if (factory == null) {
            throw new IllegalStateException(
                    "No ChatModel beans nor DynamicChatModelFactory present. " +
                            "Provide @Bean utilityChatModel/moeChatModel or enable DynamicChatModelFactory."
            );
        }
    }
}