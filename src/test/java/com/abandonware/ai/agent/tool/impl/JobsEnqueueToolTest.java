package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.job.DurableJobService;
import com.abandonware.ai.agent.job.InMemoryJobQueue;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobsEnqueueToolTest {

    @Test
    void nonMapPayloadFallsBackToEmptyPayload() {
        InMemoryJobQueue queue = new InMemoryJobQueue();
        JobsEnqueueTool tool = new JobsEnqueueTool(new DurableJobService(queue));

        var response = tool.execute(new ToolRequest(Map.of(
                "flow", "demo-flow",
                "payload", "not-a-map",
                "requestId", "rid-1",
                "sessionId", "sid-1"
        ), new ToolContext("sid-1", null)));

        assertThat(response.data().get("jobId")).isInstanceOf(String.class);
        var record = queue.dequeue("demo-flow", 0);
        assertThat(record).isPresent();
        assertThat(record.orElseThrow().request().payload()).isEmpty();
    }
}
