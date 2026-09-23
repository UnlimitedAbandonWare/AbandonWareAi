package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuxDownTrackerRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void auxDownBreadcrumbsRedactSourceReasonAndErrorMessage() {
        String rawSecret = "test-token-auxdowntrackersecret1234567890";

        AuxDownTracker.markDegraded(
                "source Authorization Bearer " + rawSecret,
                "reason api_key=" + rawSecret,
                new IllegalStateException("failed client_secret=" + rawSecret));

        String dump = String.valueOf(TraceStore.getAll());

        assertFalse(dump.contains(rawSecret), dump);
        assertFalse(dump.contains("Authorization Bearer " + rawSecret), dump);
        assertFalse(dump.contains("api_key=" + rawSecret), dump);
        assertFalse(dump.contains("client_secret=" + rawSecret), dump);
    }

    @Test
    void auxDownBreadcrumbsHashFreeTextReason() {
        String rawReason = "student private query fallback reason";
        GuardContext context = new GuardContext();
        GuardContextHolder.set(context);

        AuxDownTracker.markDegraded("query-transformer", rawReason);

        String dump = String.valueOf(TraceStore.getAll());
        String bypassReason = String.valueOf(context.getBypassReason());

        assertFalse(dump.contains(rawReason), dump);
        assertFalse(bypassReason.contains(rawReason), bypassReason);
        assertTrue(dump.contains("hash:"), dump);
        assertTrue(bypassReason.contains("hash:"), bypassReason);
    }

    @Test
    void auxDownFailSoftPathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/infra/resilience/AuxDownTracker.java"));

        assertTrue(source.contains("traceSuppressed(\"auxDown.softTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"auxDown.guardContext\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"auxDown.traceAppend\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"aux.down.suppressed.\" + safeStage, true);"));
    }
}
