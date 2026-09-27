package com.example.lms.api;

import com.example.lms.service.rag.feedback.FeedbackBlocklistRegistry;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SearchFeedbackControllerInputTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void feedbackRejectsNullPayloadWithoutTouchingRegistry() {
        FeedbackBlocklistRegistry registry = mock(FeedbackBlocklistRegistry.class);
        SearchFeedbackController controller = new SearchFeedbackController(registry);

        var response = controller.feedback(null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, TraceStore.get("api.searchFeedback.rejected"));
        assertEquals(1L, TraceStore.get("api.searchFeedback.rejected.count"));
        assertEquals("missing_payload", TraceStore.get("api.searchFeedback.skipped.reason"));
        verifyNoInteractions(registry);
    }
}
