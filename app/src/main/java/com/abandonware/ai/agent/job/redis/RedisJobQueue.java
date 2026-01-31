package com.abandonware.ai.agent.job.redis;

import com.abandonware.ai.agent.job.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.job.redis.RedisJobQueue
 * Role: config
 * Dependencies: com.abandonware.ai.agent.job.*, com.fasterxml.jackson.databind.ObjectMapper
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.job.redis.RedisJobQueue
role: config
*/
public class RedisJobQueue implements JobQueue {
    private final StringRedisTemplate redis;
    private final ObjectMapper om;

    public RedisJobQueue(StringRedisTemplate redis, ObjectMapper om) {
        this.redis = redis;
        this.om = om;
    }

    private static String q(String flow){ return "jobs:q:" + flow; }
    private static String rec(String id){ return "jobs:rec:" + id; }
    private static String dlq(String flow){ return "jobs:dlq:" + flow; }

    @Override
    public String enqueue(JobRequest req) {
        String id = UUID.randomUUID().toString();
        JobRecord record = new JobRecord(new JobId(), req);
        try {
            redis.opsForValue().set(rec(id), om.writeValueAsString(record));
            redis.opsForList().leftPush(q(req.flow()), id);
            return id;
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue job", e);
        }
    }

    @Override
    public Optional<JobRecord> dequeue(String flow, long blockMillis) {
        String jobId = redis.opsForList().rightPop(q(flow), Duration.ofMillis(blockMillis));
        if (jobId == null) return Optional.empty();
        try {
            String json = redis.opsForValue().get(rec(jobId));
            if (json == null) return Optional.empty();
            JobRecord record = om.readValue(json, JobRecord.class);
            // Mark as RUNNING (best-effort)
            record.setState(JobState.RUNNING);
            redis.opsForValue().set(rec(jobId), om.writeValueAsString(record));
            return Optional.of(record);
        } catch (Exception e) {
            throw new RuntimeException("Failed to dequeue job", e);
        }
    }

    @Override
    public void ackSuccess(String jobId, JobResult result) {
        try {
            String key = rec(jobId);
            String json = redis.opsForValue().get(key);
            if (json != null) {
                JobRecord r = om.readValue(json, JobRecord.class);
                r.setResult(result);
                r.setState(JobState.SUCCEEDED);
                redis.opsForValue().set(key, om.writeValueAsString(r));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to ack success", e);
        }
    }

    @Override
    public void ackFailure(String jobId, String reason, boolean toDlq) {
        try {
            String key = rec(jobId);
            String json = redis.opsForValue().get(key);
            if (json != null) {
                JobRecord r = om.readValue(json, JobRecord.class);
                r.setState(toDlq ? JobState.DLQ : JobState.FAILED);
                redis.opsForValue().set(key, om.writeValueAsString(r));
                if (toDlq) {
                    // Push just the ID to the DLQ list
                    // Note: We store state in jobs:rec:{jobId}, the DLQ list is for consumption/inspection.
                    redis.opsForList().leftPush(dlq(r.request().flow()), jobId);
                    redis.expire(dlq(r.request().flow()), java.time.Duration.ofDays(3));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to ack failure", e);
        }
    }
}