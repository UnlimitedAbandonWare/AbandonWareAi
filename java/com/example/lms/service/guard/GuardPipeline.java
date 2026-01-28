package com.example.lms.service.guard;

import com.example.lms.service.routing.RouteSignal;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.Function;




@Component
@RequiredArgsConstructor
public class GuardPipeline {

    // ❌ EvidenceAwareGuard 주입 안 받음(임시 no-op 파이프라인)

    public String guardOrRegenerate(
            String draft,
            List<EvidenceAwareGuard.EvidenceDoc> evidence,
            Function<RouteSignal, ChatModel> escalator,
            RouteSignal signal,
            int maxRegens
    ) {
        // 지금은 그대로 통과만
        return draft;
    }
}
// Hypernova patch hint: Insert DPP diversity reranking between BiEncoder and Cross-Encoder.