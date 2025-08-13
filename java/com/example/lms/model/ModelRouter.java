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

    /** 상위 모델로 라우팅할 의도 집합(확장) */
    private static final Set<String> HIGH_TIER_INTENTS = Set.of(
            "PAIRING", "RECOMMENDATION", "EXPLANATION", "TUTORIAL", "ANALYSIS"
    );

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

    /** 의도 기반 모델 라우팅(기존 호환) */
    public ChatModel route(@Nullable String intent) {
        boolean highTier = intent != null && HIGH_TIER_INTENTS.contains(intent.toUpperCase());
        return highTier ? resolveMoe() : resolveBase();
    }

    /**
     * 의도 + 리스크 + 상세도 기반 라우팅
     * - high intent(위 집합) OR risk=HIGH OR verbosity in {deep, ultra} → 상위(MOE)
     * - 토큰 예산 힌트가 큰 경우(예: 1536+ )도 상위로 승격
     */
    public ChatModel route(@Nullable String intent,
                           @Nullable String riskLevel,
                           @Nullable String verbosityHint,
                           @Nullable Integer targetMaxTokens) {

        boolean highTierIntent = intent != null && HIGH_TIER_INTENTS.contains(intent.toUpperCase());
        boolean highRisk       = "HIGH".equalsIgnoreCase(riskLevel);
        boolean highVerbosity  = verbosityHint != null
                && ("deep".equalsIgnoreCase(verbosityHint) || "ultra".equalsIgnoreCase(verbosityHint));

        // 토큰 예산이 큰 경우 상위 모델 선호 (휴리스틱)
        boolean bigBudget = targetMaxTokens != null && targetMaxTokens >= 1536;

        boolean useMoe = highTierIntent || highRisk || highVerbosity || bigBudget;

        if (log.isDebugEnabled()) {
            log.debug("ModelRouter decision intent={}, risk={}, verbosity={}, maxTokens={}, useMoe={}",
                    intent, riskLevel, verbosityHint, targetMaxTokens, useMoe);
        }

        return useMoe ? resolveMoe() : resolveBase();
    }

    /** 상위(MOE) 모델 해석 */
    private ChatModel resolveMoe() {
        if (moe != null) return moe;                       // 주입 우선
        ChatModel cached = cachedMoe.get();
        if (cached != null) return cached;                 // 캐시 사용
        ensureFactory();                                   // 없으면 동적 생성
        return cachedMoe.updateAndGet(existing ->
                existing != null ? existing
                        // moe: 낮은 temperature로 일관성/사실성 강화
                        : factory.lc(moeModelName, 0.3, 1.0, null)
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