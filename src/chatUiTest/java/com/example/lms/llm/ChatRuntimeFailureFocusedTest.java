package com.example.lms.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatRuntimeFailureFocusedTest {
    @Test void linkageFailureRetainsRecoveryMeaningThroughWrapping() {
        for (Throwable failure : new Throwable[]{new NoClassDefFoundError("private"),
                new ExceptionInInitializerError("private"), new UnsupportedClassVersionError("private"),
                new NoSuchMethodError("private")}) {
            assertEquals("backend_unavailable", ModelSelectionException.streamFailureCode(failure));
            assertEquals("backend_unavailable", ModelSelectionException.streamFailureCode(
                    new IllegalStateException("private", failure)));
        }
    }

    @Test void ordinaryErrorsAndTypedTimeoutRemainDistinct() {
        assertEquals("stream_failed", ModelSelectionException.streamFailureCode(new IllegalStateException("private")));
        assertEquals("backend_timeout", ModelSelectionException.streamFailureCode(
                new RuntimeException("private", new ModelSelectionException("backend_timeout"))));
        assertEquals("stream_failed", ModelSelectionException.streamFailureCode(null));
    }
}
