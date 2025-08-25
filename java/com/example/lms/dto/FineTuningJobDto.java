package com.example.lms.dto;

import java.time.OffsetDateTime;

public record FineTuningJobDto(
        String id,
        String status,              // e.g., "queued", "running", "succeeded", "failed"
        String fineTunedModel,      // e.g., "ft:gpt-4o-mini:org:foo"
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String errorMessage         // nullable
) {}
