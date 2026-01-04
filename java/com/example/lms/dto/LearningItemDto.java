package com.example.lms.dto;

import java.util.List;
import java.util.Objects;



/**
 * A lightweight DTO representing a single learning example transmitted
 * from the client to the server.  Each learning item records the
 * user's question (q), the assistant's answer (a), a list of
 * supporting evidence (e.g. URLs) and a timestamp indicating when
 * the turn was completed.  These items are used when the client
 * opts into the client-echo learning mode, allowing the server to
 * ingest verified conversations for future model fine-tuning or
 * knowledge curation.
 */
public record LearningItemDto(
        String q,
        String a,
        List<String> evidence,
        Long ts
) {
    public LearningItemDto {
        Objects.requireNonNull(q, "q must not be null");
        Objects.requireNonNull(a, "a must not be null");
        evidence = (evidence == null) ? List.of() : List.copyOf(evidence);
        // ts may be null; when null, downstream services should assign the current time
    }
}