package com.abandonware.ai.agent.job;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import java.time.Duration;




import static org.mockito.Mockito.*;

/**
 * DLQ push 시 TTL 설정되는지 검증
 */
public class RedisJobQueueDlqTest {

    @Test
    @SuppressWarnings("unchecked")
    void dlq_shouldExpire() {
        var redis = mock(StringRedisTemplate.class);
        var listOps = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOps);

        var q = new RedisJobQueue(redis, Duration.ofDays(3));
        q.pushToDlq("flowX","job1");

        verify(listOps).leftPush(eq("dlq:flowX"), eq("job1"));
        verify(redis).expire(eq("dlq:flowX"), eq(Duration.ofDays(3)));
    }
}