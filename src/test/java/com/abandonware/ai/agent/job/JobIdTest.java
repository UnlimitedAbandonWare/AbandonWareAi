package com.abandonware.ai.agent.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JobIdTest {

    @Test
    void explicitBlankOrNullValueFallsBackToGeneratedId() {
        JobId nullId = new JobId(null);
        JobId blankId = new JobId(" ");

        assertFalse(nullId.value().isBlank());
        assertFalse(blankId.value().isBlank());
        assertNotEquals(nullId, blankId);
    }

    @Test
    void explicitNonBlankValueIsTrimmedAndPreserved() {
        JobId id = new JobId(" job-123 ");

        assertEquals("job-123", id.value());
    }
}
