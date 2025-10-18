package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;



public interface ModelRouter {

    // 이미 있는 시그니처
    ChatModel route(RouteSignal sig);

    // 🔹 ChatService가 직접 호출하는 오버로드 – 반드시 인터페이스에 선언
    ChatModel route(String intent,
                    String riskLevel,
                    String verbosityHint,
                    Integer targetMaxTokens);

    // 🔹 EvidenceAwareGuard에서 부르는 승격 API는 ChatModel을 반환해야 함
    ChatModel escalate(RouteSignal sig);

    // 🔹 실제 모델명 노출
    String resolveModelName(ChatModel model);
}