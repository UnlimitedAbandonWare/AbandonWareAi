package com.abandonware.ai.agent;

import com.abandonware.ai.agent.job.JobRecord;
import com.abandonware.ai.agent.job.JobRequest;
import com.abandonware.ai.agent.job.JobResult;
import com.abandonware.ai.agent.job.JobState;
import com.abandonware.ai.agent.job.redis.RedisJobQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;




import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.RedisJobQueueTest
 * Role: config
 * Feature Flags: sse
 * Dependencies: com.abandonware.ai.agent.job.JobRecord, com.abandonware.ai.agent.job.JobRequest, com.abandonware.ai.agent.job.JobResult, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.RedisJobQueueTest
role: config
flags: [sse]
*/
public class RedisJobQueueTest {

    @Test
    void enqueueDequeueAckRoundtrip() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForValue()).thenReturn(valueOps);

        ObjectMapper om = new ObjectMapper();
        RedisJobQueue q = new RedisJobQueue(redis, om);

        // Capture the jobId pushed during enqueue
        ArgumentCaptor<String> listKeyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jobIdCap = ArgumentCaptor.forClass(String.class);
        when(listOps.leftPush(listKeyCap.capture(), jobIdCap.capture())).thenReturn(1L);

        String flow = "kakao/ask";
        String jobId = q.enqueue(new JobRequest(flow, Map.of("a","b"), "req-1", "sess-1"));
        assertNotNull(jobId);
        assertEquals(jobId, jobIdCap.getValue());

        // Prepare stored record JSON
        JobRecord rec = new JobRecord(jobId, new JobRequest(flow, Map.of("a","b"), "req-1", "sess-1"));
        rec.setState(JobState.PENDING);
        String recKey = "jobs:rec:" + jobId;
        String recJson = om.writeValueAsString(rec);
        when(valueOps.get(recKey)).thenReturn(recJson);

        // Simulate blocking pop returning the same jobId
        when(listOps.rightPop("jobs:q:" + flow, Duration.ofMillis(1000))).thenReturn(jobId);

        Optional<JobRecord> got = q.dequeue(flow, 1000);
        assertTrue(got.isPresent());
        assertEquals(jobId, got.get().id());
        assertEquals(JobState.PENDING, got.get().state());

        // Ack success: verify valueOps.set called with SUCCESS state
        JobResult result = new JobResult(Map.of("ok", true), null);
        q.ackSuccess(jobId, result);
        verify(valueOps, atLeastOnce()).set(eq(recKey), contains("SUCCEEDED"));

        // Ack failure to DLQ: verify DLQ push and state
        q.ackFailure(jobId, "x", true);
        verify(listOps, atLeastOnce()).leftPush(eq("jobs:dlq:" + flow), eq(jobId));
        verify(valueOps, atLeastOnce()).set(eq(recKey), contains("DLQ"));
    }
}