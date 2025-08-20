package com.example.lms.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Adapter that delegates all model routing decisions to the unified
 * {@link com.example.lms.service.routing.ModelRouter} implementation.
 * This class preserves the historical bean name "modelRouter" used in
 * other parts of the application while ensuring that there is only a
 * single authoritative router implementation. The delegate is
 * injected by Spring.
 */
@Component("modelRouter")
@RequiredArgsConstructor
public class ModelRouter {

    /**
     * The unified router implementation to which all calls are delegated. The
     * qualifier ensures that the bean named "modelRouterCore" is injected
     * rather than any other {@link com.example.lms.service.routing.ModelRouter}
     * candidates that may exist on the classpath.
     */
    private final @Qualifier("modelRouterCore") com.example.lms.service.routing.ModelRouter delegate;

    /**
     * Delegates the routing decision to the unified router.
     *
     * @param sig the composite signal used for routing
     * @return the chosen {@link ChatModel}
     */
    public ChatModel route(com.example.lms.service.routing.RouteSignal sig) {
        return delegate.route(sig);
    }

    /**
     * Convenience overload mirroring {@link com.example.lms.service.routing.ModelRouter#route(String)}.
     * Delegates to the core router with zeroed numeric heuristics.
     *
     * @param intent caller provided intent
     * @return the chosen chat model
     */
    public ChatModel route(String intent) {
        return delegate.route(intent);
    }

    /**
     * Convenience overload mirroring {@link com.example.lms.service.routing.ModelRouter#route(String, String, String, Integer)}.
     * Delegates to the core router to perform the full translation to a {@link com.example.lms.service.routing.RouteSignal}.
     *
     * @param intent intent hint
     * @param riskLevel risk level hint
     * @param verbosity verbosity hint
     * @param maxTokens maximum token budget
     * @return the chosen chat model
     */
    public ChatModel route(String intent, String riskLevel, String verbosity, Integer maxTokens) {
        return delegate.route(intent, riskLevel, verbosity, maxTokens);
    }

    /**
     * ✅ [추가된 메소드]
     * 모델명을 보고/로그용으로 조회합니다. core 라우터에 구현이 있으면 위임하고,
     * 없으면 예외 발생 시 안전하게 기본 모델명을 반환합니다.
     *
     * @param model 조회할 ChatModel 객체
     * @return 확인된 모델명 문자열
     */
    public String resolveModelName(ChatModel model) {
        // 'core' 대신 기존 변수명인 'delegate'를 사용하도록 수정
        try {
            // com.example.lms.model.ModelRouter의 resolveModelName을 호출 시도
            // (해당 클래스가 있다면)
            java.lang.reflect.Method method = delegate.getClass().getMethod("resolveModelName", ChatModel.class);
            return (String) method.invoke(delegate, model);
        } catch (Exception ignore) {
            // 메소드가 없거나 호출에 실패하면 안전한 기본값 반환
            return "lc:" + (model == null ? "unknown" : model.getClass().getSimpleName());
        }
    }
}