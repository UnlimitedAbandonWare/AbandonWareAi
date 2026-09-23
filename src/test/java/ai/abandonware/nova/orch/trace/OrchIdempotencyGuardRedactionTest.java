package ai.abandonware.nova.orch.trace;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchIdempotencyGuardRedactionTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void violationTraceDoesNotExposeRawScopeOrMeta() {
        String fakeSecret = "sk-" + "A".repeat(24);
        String rawScope = "ownertoken " + fakeSecret;

        assertTrue(OrchIdempotencyGuard.check(null, rawScope, "same-key", "digest-a",
                Map.of("rawPrompt", "private prompt " + fakeSecret)));
        assertFalse(OrchIdempotencyGuard.check(null, rawScope, "same-key", "digest-b",
                Map.of("rawPrompt", "private prompt " + fakeSecret)));

        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains(fakeSecret), rendered);
        assertFalse(rendered.toLowerCase().contains("ownertoken"), rendered);
        assertFalse(rendered.contains("private prompt"), rendered);
        assertTrue(rendered.contains("hash_"), rendered);
    }
}
