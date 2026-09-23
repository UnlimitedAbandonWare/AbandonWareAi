package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestTimelineRetentionPolicyTest {

    @Test
    void insertionAtCapacityEvictsOnlyOldestTimeline() {
        RequestTimelineRetentionPolicy policy = new RequestTimelineRetentionPolicy(3);
        LinkedHashMap<String, String> timelines = new LinkedHashMap<>();
        policy.retain(timelines, "one", "1");
        policy.retain(timelines, "two", "2");
        policy.retain(timelines, "three", "3");

        policy.retain(timelines, "four", "4");

        assertEquals(3, timelines.size());
        assertFalse(timelines.containsKey("one"));
        assertEquals("two", timelines.keySet().iterator().next());
        assertEquals("4", timelines.get("four"));
    }

    @Test
    void replacingExistingTimelineDoesNotEvictAnotherEntry() {
        RequestTimelineRetentionPolicy policy = new RequestTimelineRetentionPolicy(2);
        LinkedHashMap<String, String> timelines = new LinkedHashMap<>();
        policy.retain(timelines, "one", "1");
        policy.retain(timelines, "two", "2");

        policy.retain(timelines, "one", "updated");

        assertEquals(2, timelines.size());
        assertEquals("updated", timelines.get("one"));
        assertEquals("2", timelines.get("two"));
    }

    @Test
    void nonPositiveCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RequestTimelineRetentionPolicy(0));
        assertThrows(IllegalArgumentException.class, () -> new RequestTimelineRetentionPolicy(-1));
    }
}
