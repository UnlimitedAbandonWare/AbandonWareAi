package com.example.lms.uaw.thumbnail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * UAW Thumbnail 플랜 스펙.
 *
 * <p>classpath: plans/UAW_thumbnail.v1.yaml 로부터 로드됩니다.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UawThumbnailPlanSpec(
        String id,
        Integer version,
        String kind,
        Anchors anchors,
        Evidence evidence,
        Render render,
        Persist persist
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Anchors(
            Integer count,
            String model,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(
            @JsonProperty("web_topk_per_anchor") Integer webTopKPerAnchor,
            @JsonProperty("evidence_topk_per_anchor") Integer evidenceTopKPerAnchor,
            @JsonProperty("final_k") Integer finalK,
            @JsonProperty("require_unique_domains") Boolean requireUniqueDomains
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Render(
            String mode,
            String model,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Persist(
            String domain,
            @JsonProperty("entity_type") String entityType,
            @JsonProperty("min_confidence") Double minConfidence
    ) {
    }
}
