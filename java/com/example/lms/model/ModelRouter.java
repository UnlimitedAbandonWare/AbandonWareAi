
        package com.example.lms.model;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.service.routing.RouteSignal;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.springframework.lang.Nullable;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component("modelRouterLegacy")
// 👇 [변경] ModelRouter 인터페이스를 구현하도록 수정
public class ModelRouter implements com.example.lms.service.routing.ModelRouter {

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
    @Nullable private final ChatModel utility;
    @Nullable private final ChatModel moe;

    /** 동적 생성 시 사용할 모델명(프로퍼티로 설정, 기본값 포함) */
    private final String moeModelName;
    private final String baseModelName;

    /** 동적 생성 캐시 */
    private final AtomicReference<ChatModel> cachedMoe  = new AtomicReference<>();
    private final AtomicReference<ChatModel> cachedBase = new AtomicReference<>();

    public ModelRouter(
            @Nullable DynamicChatModelFactory factory,
            @Nullable @Qualifier("mini") ChatModel utility,
            @Nullable @Qualifier("high") ChatModel moe,
            @Value("${router.moe.high:${openai.model.moe:gpt-4o}}") String moeModelName,
            @Value("${router.moe.mini:${langchain4j.openai.chat-model.model-name:gpt-4o-mini}}") String baseModelName,
            @Value("${router.moe.learning-intents:}") String learningIntentsStr
    ) {
        this.factory = factory;
        this.utility = utility;
        this.moe = moe;
        this.moeModelName = moeModelName;
        this.baseModelName = baseModelName;
        java.util.Set<String> tmp = new java.util.HashSet<>();
        if (learningIntentsStr != null && !learningIntentsStr.isBlank()) {
            for (String s : learningIntentsStr.split("[,\\s+]")) {
                if (!s.isBlank()) tmp.add(s.trim().toUpperCase());
            }
        }
        this.learningIntents = java.util.Collections.unmodifiableSet(tmp);
    }

    /**
     * 👇 [추가] 인터페이스의 기본 라우팅 메서드 구현
     * RouteSignal 객체를 받아 기존의 상세 라우팅 메서드로 정보를 넘겨줍니다.
     */
    @Override
    public ChatModel route(RouteSignal sig) {
        if (sig == null) {
            return resolveBase(); // 시그널이 없으면 기본 모델 반환
        }

        // RouteSignal에 riskLevel 정보가 없으므로 null로 전달합니다.
        // intent나 verbosity가 enum 타입일 경우를 대비해 null-safe하게 문자열로 변환합니다.
        String intent = (sig.intent() != null) ? sig.intent().toString() : null;
        String verbosity = (sig.verbosity() != null) ? sig.verbosity().toString() : null;

        // 토큰 힌트는 일단 생략(null). 필요하면 RouteSignal에서 안전하게 꺼내는 헬퍼를 나중에 추가.
        return this.route(intent, null, verbosity, null);
    }

    /** 의도 기반 모델 라우팅(단순 호환용) */
    public ChatModel route(@Nullable String intent) {
        boolean highTier = intent != null && HIGH_TIER_INTENTS.contains(intent.toUpperCase());
        return highTier ? resolveMoe() : resolveBase();
    }

    /**
     * 👇 [변경] @Override 추가 (인터페이스의 default 메서드를 재정의)
     * 의도 + 리스크 + 상세도 기반 라우팅
     */
    @Override
    public ChatModel route(@Nullable String intent,
                           @Nullable String riskLevel,
                           @Nullable String verbosityHint,
                           @Nullable Integer targetMaxTokens) {

        boolean highTierIntent = intent != null && HIGH_TIER_INTENTS.contains(intent.toUpperCase());
        boolean learningIntent  = intent != null && learningIntents.contains(intent.toUpperCase());
        boolean highRisk       = "HIGH".equalsIgnoreCase(riskLevel);
        boolean highVerbosity  = verbosityHint != null
                && ("deep".equalsIgnoreCase(verbosityHint) || "ultra".equalsIgnoreCase(verbosityHint));
        boolean bigBudget = targetMaxTokens != null && targetMaxTokens >= 1536;
        boolean useMoe = highTierIntent || highRisk || highVerbosity || bigBudget || learningIntent;

        if (log.isDebugEnabled()) {
            log.debug("ModelRouter decision intent={}, risk={}, verbosity={}, maxTokens={}, useMoe={}",
                    intent, riskLevel, verbosityHint, targetMaxTokens, useMoe);
        }

        return useMoe ? resolveMoe() : resolveBase();
    }

    private ChatModel resolveMoe() {
        if (moe != null) return moe;
        ChatModel cached = cachedMoe.get();
        if (cached != null) return cached;
        ensureFactory();
        return cachedMoe.updateAndGet(existing ->
                existing != null ? existing
                        : factory.lc(moeModelName, 0.3, 1.0, null)
        );
    }

    private ChatModel resolveBase() {
        if (utility != null) return utility;
        ChatModel cached = cachedBase.get();
        if (cached != null) return cached;
        ensureFactory();
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

    /**
     * 👇 [변경] @Override 추가 (인터페이스의 default 메서드를 재정의)
     * 실제 SDK에 내려간 모델명 해석(가능한 한 정확히)
     */
    @Override
    public String resolveModelName(ChatModel model) {
        if (factory == null || model == null) {
            return (model == null) ? "unknown" : model.getClass().getSimpleName();
        }
        return factory.effectiveModelName(model);
    }

    /**
     * 👇 [추가] escalate 메서드 오버라이드 (선택 사항이지만 명시적으로 추가)
     * 기본 동작은 no-op (아무것도 안 함)
     */
    @Override
    public ChatModel escalate(RouteSignal sig) {
        // 증거 불충분/커버리지 부족 등의 신호가 오면 상위(MOE)로 즉시 승격
        return resolveMoe();
    }
}
