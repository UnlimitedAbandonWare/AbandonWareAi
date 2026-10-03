package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;



public interface ModelRouter {

    // 이미 있는 시그니처
    ChatModel route(RouteSignal sig);

    /** Main generation only; utility/judge callers retain the existing route methods. */
    default ChatModel routeMain(String intent, String riskLevel, String verbosityHint,
            Integer targetMaxTokens, String requestedModel, String query, boolean toolsRequired) {
        return route(intent, riskLevel, verbosityHint, targetMaxTokens, requestedModel);
    }

    /** Optional equipment for the already frozen main route; never selects or calls another model. */
    default com.example.lms.prompt.pose.ModelLoadoutResolver.ResolvedLoadout selectLoadout(
            com.example.lms.prompt.pose.ModelLoadoutResolver.Request request, ChatModel selected) {
        return null;
    }

    // 🔹 ChatService가 직접 호출하는 오버로드 - 반드시 인터페이스에 선언
    ChatModel route(String intent,
                    String riskLevel,
                    String verbosityHint,
                    Integer targetMaxTokens);

    /**
     * Optional overload that allows callers to pass the user-requested model id.
     *
     * <p>Implementations may override this method to build a dynamic model
     * instance (e.g. per-request modelName) while still using the same tier
     * selection logic as the 4-arg route().
     *
     * <p>Default behaviour: ignore requestedModel and delegate to the
     * 4-arg route().
     */
    default ChatModel route(String intent,
                            String riskLevel,
                            String verbosityHint,
                            Integer targetMaxTokens,
                            String requestedModel) {
        return route(intent, riskLevel, verbosityHint, targetMaxTokens);
    }

    // 🔹 EvidenceAwareGuard에서 부르는 승격 API는 ChatModel을 반환해야 함
    ChatModel escalate(RouteSignal sig);

    // 🔹 실제 모델명 노출
    String resolveModelName(ChatModel model);
}
