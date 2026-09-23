package com.abandonware.ai.agent.service.plan;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlanLoaderTraceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void malformedPlanFallsBackWithRedactedBreadcrumb() throws Exception {
        Path plan = tempDir.resolve("private-plan.yml");
        Files.writeString(plan, """
                id: private-plan
                calibration:
                  recency:
                    halfLifeDays: private-days
                """);

        assertThat(new PlanLoader().loadAll(plan.toUri().toString())).isEmpty();

        assertThat(TraceStore.get("agent.planLoader.suppressed")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("agent.planLoader.suppressed.stage")).isEqualTo("loadAll");
        assertThat(TraceStore.get("agent.planLoader.suppressed.errorType")).isEqualTo("ClassCastException");
        assertThat(TraceStore.get("agent.planLoader.suppressed.patternHash")).asString().startsWith("hash:");
        assertThat(TraceStore.get("agent.planLoader.suppressed.patternLength"))
                .isEqualTo(plan.toUri().toString().length());
        assertThat(String.valueOf(TraceStore.getAll())).doesNotContain(
                plan.toString(),
                plan.toUri().toString(),
                "private-days",
                "private-plan");
    }

    @Test
    void malformedPlanDoesNotDropValidNeighbor() throws Exception {
        Path malformed = tempDir.resolve("private-plan.yml");
        Files.writeString(malformed, """
                id: private-plan
                calibration:
                  recency:
                    halfLifeDays: private-days
                """);
        Path valid = tempDir.resolve("safe-plan.yml");
        Files.writeString(valid, """
                id: safe-plan
                desc: safe
                k:
                  web: 3
                """);

        var plans = new PlanLoader().loadAll(tempDir.toUri() + "*.yml");

        assertThat(plans).containsOnlyKeys("safe-plan");
        assertThat(plans.get("safe-plan").k()).containsEntry("web", 3);
        assertThat(TraceStore.get("agent.planLoader.suppressed")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(TraceStore.getAll())).doesNotContain(
                malformed.toString(),
                malformed.toUri().toString(),
                "private-days",
                "private-plan");
    }
}
