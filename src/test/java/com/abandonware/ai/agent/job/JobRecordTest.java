package com.abandonware.ai.agent.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JobRecordTest {

    @Test
    void constructorProvidesSafeDefaultsForNullIdAndRequest() {
        JobRecord record = new JobRecord(null, null);

        assertNotNull(record.id());
        assertEquals("default", record.request().flow());
        assertEquals(JobState.PENDING, record.state());
    }

    @Test
    void nullStateFallsBackToPending() {
        JobRecord record = new JobRecord(new JobId("job-1"), new JobRequest("flow", null, null, null));

        record.setState(null);

        assertEquals(JobState.PENDING, record.state());
    }
}
