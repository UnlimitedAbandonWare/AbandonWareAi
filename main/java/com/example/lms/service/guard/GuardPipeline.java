package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import com.example.lms.service.routing.RouteSignal;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class GuardPipeline {

    private static final Logger log = LoggerFactory.getLogger(GuardPipeline.class);

    private final EvidenceAwareGuard evidenceAwareGuard;
    private final PIISanitizer piiSanitizer = new PIISanitizer();

    public String guardOrRegenerate(
            String draft,
            List<EvidenceAwareGuard.EvidenceDoc> evidence,
            Function<RouteSignal, ChatModel> escalator,
            RouteSignal signal,
            int maxRegens
    ) {
        String safeDraft = mask(draft);
        List<EvidenceAwareGuard.EvidenceDoc> safeEvidence = evidence == null ? List.of() : evidence.stream()
                .filter(Objects::nonNull)
                .toList();

        try {
            EvidenceAwareGuard.GuardDecision decision = evidenceAwareGuard.guardWithEvidence(
                    safeDraft,
                    safeEvidence,
                    Math.max(0, maxRegens)
            );
            TraceStore.put("guard.pipeline.decision", decision.action().name());
            TraceStore.put("guard.pipeline.evidenceCount", safeEvidence.size());
            TraceStore.put("guard.pipeline.coverageScore", decision.coverageScore());
            TraceStore.put("guard.pipeline.degradedToEvidence", decision.degradedToEvidence());
            log.debug("[AWX][guard] decision={} evidenceCount={} degraded={} redacted=true",
                    decision.action(), safeEvidence.size(), decision.degradedToEvidence());
            return mask(decision.finalDraft());
        } catch (Exception ex) {
            TraceStore.put("guard.pipeline.disabledReason", "guard_exception");
            TraceStore.put("guard.pipeline.errorType", "guard_pipeline_failed");
            log.warn("[AWX][guard] disabledReason=guard_exception errorType={} redacted=true",
                    "guard_pipeline_failed");
            return safeDraft;
        }
    }

    private String mask(String value) {
        return piiSanitizer.mask(value == null ? "" : value);
    }
}
