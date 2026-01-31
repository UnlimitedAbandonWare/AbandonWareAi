package com.example.lms.service.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;




/**
 * A simple Redis-backed lock and cooldown service.  It provides an
 * atomic {@code SET key value NX EX ttl} operation to acquire a lock or
 * schedule a cooldown and exposes utility methods to compute exponential
 * backoff delays with jitter.  Separating the lock and cooldown keys
 * ensures that long cooldown periods do not block short, high-contention
 * locks.
 *
 * <p>This service is intentionally lightweight and does not support
 * connection pooling or Redis clusters.  For production use cases consider
 * injecting a preconfigured {@link Jedis} instance or using Spring Data
 * Redis abstractions.</p>
 */
@Service
public class RedisCooldownService {

    private final Jedis jedis;

    public RedisCooldownService(@Value("${redis.host:localhost}") String host,
                                @Value("${redis.port:6379}") int port) {
        this.jedis = new Jedis(host, port);
    }

    /**
     * Atomically sets the given key to hold the specified value with a TTL
     * only if the key does not already exist.  This maps directly onto the
     * Redis {@code SET key value NX EX ttl} command which guarantees
     * atomicity without requiring separate {@code SETNX} and {@code EXPIRE}
     * calls.  When the operation succeeds the key will expire after the
     * requested number of seconds.  If the key already exists the method
     * returns {@code false} and no changes are made.
     *
     * @param key      the Redis key to set
     * @param value    the value to associate with the key
     * @param ttlSecs  the time-to-live in seconds
     * @return {@code true} if the key was set, {@code false} otherwise
     */
    public boolean setNxEx(String key, String value, long ttlSecs) {
        SetParams params = SetParams.setParams().nx().ex(Math.toIntExact(ttlSecs));
        String result = jedis.set(key, value, params);
        return "OK".equalsIgnoreCase(result);
    }

    /**
     * Compute an exponential backoff delay with optional full or equal jitter.
     * The backoff grows exponentially with the attempt number and is bounded
     * between 0 and {@code baseDelay * 2^attempt}.  When {@code fullJitter}
     * is {@code true} the delay is uniformly random between 0 and the upper
     * bound; when {@code false} the delay is split into a deterministic
     * component and a random component between half the bound and the bound
     * itself.  See the AWS Architecture Blog for details on equal vs full
     * jitter strategies.
     *
     * @param attempt    the zero-based attempt count (non-negative)
     * @param baseDelay  the base delay duration; must be positive
     * @param fullJitter whether to use full jitter (true) or equal jitter (false)
     * @return a {@link Duration} representing the computed delay
     */
    public Duration jitteredBackoff(int attempt, Duration baseDelay, boolean fullJitter) {
        if (attempt < 0) attempt = 0;
        long baseMillis = Math.max(1L, baseDelay.toMillis());
        // Compute the exponential upper bound.  Cap the exponent to avoid
        // overflow when attempt is large.
        int cappedAttempt = Math.min(attempt, 16); // prevents overflow at 2^16
        long upperBound = baseMillis << cappedAttempt;
        // Guard against overflow
        if (upperBound <= 0) {
            upperBound = Long.MAX_VALUE;
        }
        long delay;
        if (fullJitter) {
            delay = ThreadLocalRandom.current().nextLong(0L, upperBound + 1);
        } else {
            long half = upperBound / 2;
            delay = half + ThreadLocalRandom.current().nextLong(0L, half + 1);
        }
        return Duration.ofMillis(delay);
    }

    /**
     * Close the underlying Jedis connection.  This should be invoked
     * gracefully on shutdown to release network resources.  Note that
     * {@link Jedis} is not thread safe; for multi-threaded scenarios use
     * {@code JedisPool} instead.
     */
    public void close() {
        try {
            jedis.close();
        } catch (Exception ignored) {
            // swallow exceptions during close
        }
    }
}