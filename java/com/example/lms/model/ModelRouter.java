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

    /**
     * 의도에 따라 학습 용도로 사용되는 태스크 집합입니다. application.yml의
     * router.moe.learning-intents 키에서 주입됩니다. 기본값은 빈 집합으로,
     * 구성에서 제공되는 값은 대문자로 변환하여 저장됩니다.
     */
    private final Set<String> learningIntents;

    /** 선택적으로 주입될 수 있는 동적 팩토리 */
    @Nullable private final DynamicChatModelFactory factory;

    /** 선택적으로 주입될 수 있는 사전 구성 모델(있으면 최우선 사용) */
    @Nullable private final ChatModel utility; // 기본 (@Primary 여도 Qualifier로 명시 주입)
    @Nullable private final ChatModel moe;     // 상위

    /** 동적 생성 시 사용할 모델명(프로퍼티로 설정, 기본값 포함) */
    private final String moeModelName;
    private final String baseModelName;

    /** 동적 생성 캐시 */
    private final AtomicReference<ChatModel> cachedMoe  = new AtomicReference<>();
    private final AtomicReference<ChatModel> cachedBase = new AtomicReference<>();

    /**
     * NOTE: @Qualifier로 명시 주입하여 @Primary utilityChatModel이
     * 암묵적으로 꽂히는 문제를 차단합니다.
     */
    public ModelRouter(
            @Nullable DynamicChatModelFactory factory,
            @Nullable @Qualifier("utilityChatModel") ChatModel utility,
            @Nullable @Qualifier("moeChatModel") ChatModel moe,
            @Value("${openai.model.moe:gpt-4o}") String moeModelName,
            @Value("${langchain4j.openai.chat-model.model-name:gpt-4o-mini}") String baseModelName,
            @Value("${router.moe.learning-intents:}") String learningIntentsStr
    ) {
        this.factory = factory;
        this.utility = utility;
        this.moe = moe;
        this.moeModelName = moeModelName;
        this.baseModelName = baseModelName;
        // 파라미터를 쉼표 또는 공백으로 분할하여 대문자로 변환
        java.util.Set<String> tmp = new java.util.HashSet<>();
        if (learningIntentsStr != null && !learningIntentsStr.isBlank()) {
            for (String s : learningIntentsStr.split("[,\\s+]")) {
                if (!s.isBlank()) tmp.add(s.trim().toUpperCase());
            }
        }
        this.learningIntents = java.util.Collections.unmodifiableSet(tmp);
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
        // 학습 의도 감지: application.yml에서 정의된 learning-intents에 포함되면 상위 모델 사용
        boolean learningIntent  = intent != null && learningIntents.contains(intent.toUpperCase());
        boolean highRisk       = "HIGH".equalsIgnoreCase(riskLevel);
        boolean highVerbosity  = verbosityHint != null
                && ("deep".equalsIgnoreCase(verbosityHint) || "ultra".equalsIgnoreCase(verbosityHint));

        // 토큰 예산이 큰 경우 상위 모델 선호 (휴리스틱)
        boolean bigBudget = targetMaxTokens != null && targetMaxTokens >= 1536;

        boolean useMoe = highTierIntent || highRisk || highVerbosity || bigBudget || learningIntent;

        if (log.isDebugEnabled()) {
            log.debug("ModelRouter decision intent={}, risk={}, verbosity={}, maxTokens={}, useMoe={}",
                    intent, riskLevel, verbosityHint, targetMaxTokens, useMoe);
        }

        return useMoe ? resolveMoe() : resolveBase();
    }

    /** 상위(MOE) 모델 해석 */
    private ChatModel resolveMoe() {
        if (moe != null) return moe;                       // 명시 주입 우선
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
        if (utility != null) return utility;               // 명시 주입 우선
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

    /** ✅ 실제 SDK에 내려간 모델명 해석(가능한 한 정확히) */
    public String resolveModelName(ChatModel model) {
        if (factory == null || model == null) {
            return (model == null) ? "unknown" : model.getClass().getSimpleName();
        }
        return factory.effectiveModelName(model);
    }
}