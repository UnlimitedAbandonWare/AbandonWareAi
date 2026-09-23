package com.abandonware.ai.agent.job;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryJobQueueTraceTest {

    @AfterEach
    void clearTrace() {
        Thread.interrupted();
        TraceStore.clear();
    }

    @Test
    void interruptedDequeueRestoresInterruptAndLeavesBreadcrumb() {
        InMemoryJobQueue queue = new InMemoryJobQueue();
        Thread.currentThread().interrupt();

        assertThat(queue.dequeue("private-flow", 100)).isEmpty();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(TraceStore.get("agent.jobQueue.dequeue.suppressed")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("agent.jobQueue.dequeue.suppressed.stage")).isEqualTo("sleep.interrupted");
        assertThat(TraceStore.get("agent.jobQueue.dequeue.suppressed.errorType")).isEqualTo("cancelled");
        assertThat(TraceStore.get("agent.jobQueue.dequeue.suppressed.flowHash")).asString().startsWith("hash:");
        assertThat(TraceStore.get("agent.jobQueue.dequeue.suppressed.flowLength")).isEqualTo(12);
        assertThat(String.valueOf(TraceStore.getAll())).doesNotContain("private-flow");
    }

    @Test
    void enqueueWithBlankFlowFallsBackToDefaultPartition() {
        InMemoryJobQueue queue = new InMemoryJobQueue();

        String jobId = queue.enqueue(new JobRequest("  ", Map.of("kind", "demo"), "rid-1", "sid-1"));

        assertThat(jobId).isNotBlank();
        assertThat(queue.dequeue("default", 0)).isPresent();
    }

    @Test
    void enqueueWithNullRequestUsesDefaultPartition() {
        InMemoryJobQueue queue = new InMemoryJobQueue();

        String jobId = queue.enqueue(null);

        assertThat(jobId).isNotBlank();
        assertThat(queue.dequeue("default", 0))
                .hasValueSatisfying(record -> assertThat(record.request().flow()).isEqualTo("default"));
    }
}
