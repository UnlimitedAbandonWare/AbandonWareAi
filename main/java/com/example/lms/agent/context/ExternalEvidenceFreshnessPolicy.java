package com.example.lms.agent.context;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

final class ExternalEvidenceFreshnessPolicy {

    private static final int DEFAULT_STALE_AFTER_MINUTES = 60;

    private final Clock clock;

    ExternalEvidenceFreshnessPolicy() {
        this(Clock.systemUTC());
    }

    ExternalEvidenceFreshnessPolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Freshness evaluate(JsonNode evidence) {
        int ageMinutes = ageMinutes(evidence);
        int staleAfterMinutes = staleAfterMinutes(evidence);
        boolean stale = evidence != null
                && (evidence.path("stale").asBoolean(false)
                || (staleAfterMinutes > 0 && ageMinutes >= staleAfterMinutes));
        return new Freshness(ageMinutes, staleAfterMinutes, stale);
    }

    private int ageMinutes(JsonNode evidence) {
        if (evidence == null) {
            return 0;
        }
        JsonNode ageNode = evidence.path("ageMinutes");
        if (!ageNode.isMissingNode() && !ageNode.isNull()) {
            return boundedMinutes(ageNode.asDouble(0.0d));
        }
        String generatedAt = evidence.path("generatedAt").asText("");
        if (generatedAt == null || generatedAt.isBlank()) {
            return 0;
        }
        try {
            long minutes = Duration.between(Instant.parse(generatedAt), clock.instant()).toMinutes();
            return minutes <= 0L ? 0 : (int) Math.min(Integer.MAX_VALUE, minutes);
        } catch (DateTimeParseException ex) {
            AgentPipelineHealthTrace.traceSuppressed("external_smoke_age", ex);
            return 0;
        }
    }

    private static int boundedMinutes(double age) {
        if (!Double.isFinite(age) || age <= 0.0d) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(age));
    }

    private static int staleAfterMinutes(JsonNode evidence) {
        if (evidence == null) {
            return 0;
        }
        int configured = Math.max(0, evidence.path("staleAfterMinutes").asInt(0));
        if (configured > 0) {
            return configured;
        }
        String generatedAt = evidence.path("generatedAt").asText("");
        return generatedAt != null && !generatedAt.isBlank()
                ? DEFAULT_STALE_AFTER_MINUTES
                : 0;
    }

    record Freshness(int ageMinutes, int staleAfterMinutes, boolean stale) {
    }
}
