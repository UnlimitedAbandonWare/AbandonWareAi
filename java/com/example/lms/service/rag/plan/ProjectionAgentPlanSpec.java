package com.example.lms.service.rag.plan;

import java.util.List;

/**
 * Strongly-typed view of plans/projection_agent.v1.yaml.
 *
 * This is intentionally narrow: we parse only the fields we actually
 * use at runtime (guard-profile, memory-profile, model, traits, ...).
 */
public record ProjectionAgentPlanSpec(
        String id,
        Defaults defaults,
        Branch viewMemorySafe,
        Branch viewFreeProjection,
        Merge merge,
        FinalAnswer finalAnswer
) {
    public record Defaults(
            String model,
            String guardProfile,
            String memoryProfile,
            Boolean citations,
            Integer maxTokens
    ) {}

    public record Branch(
            String id,
            String model,
            String guardProfile,
            String memoryProfile,
            Integer maxTokens,
            List<String> traits
    ) {}

    public record Merge(
            Boolean keepFreeSideNotes,
            Boolean keepConflictFlags
    ) {}

    public record FinalAnswer(
            String model,
            String systemPrompt,
            Boolean citations,
            Integer maxTokens
    ) {}
}
