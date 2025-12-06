package com.example.lms.infra.upstash;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Map;




/**
 * Thin client for interacting with Upstash Redis over HTTP.  Upstash
 * exposes a REST interface that accepts command pipelines.  This client
 * assembles the command payload and parses the response into a list of
 * maps, each containing a {@code result} field representing the return
 * value of the corresponding command.  The client is resilient to
 * configuration omissions; when the rest URL or token are blank the
 * {@link #enabled()} method returns {@code false} and operations short-circuit.
 */
@Component
@RequiredArgsConstructor
public class UpstashRedisClient {
    private final WebClient.Builder http;
    private final ObjectMapper om = new ObjectMapper();

    @Value("${upstash.redis.rest-url:}")
    private String url;
    @Value("${upstash.redis.rest-token:}")
    private String token;

    /**
     * Determine whether the client is properly configured.  The client is
     * considered enabled when both the REST URL and token are non-blank.
     *
     * @return {@code true} when ready for use
     */
    public boolean enabled() {
        return url != null && !url.isBlank() && token != null && !token.isBlank();
    }

    /**
     * Fetch a value from Redis by key.  When the client is disabled or an
     * error occurs an empty Mono is returned.
     *
     * @param key the Redis key to retrieve
     * @return a Mono emitting the value or empty when not found
     */
    public Mono<String> get(String key) {
        if (!enabled()) return Mono.empty();
        var body = List.of(List.of("GET", key));
        return pipeline(body)
                .map(list -> value(list, 0))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Set a value with an expiry.  Returns true on success, false on error.
     *
     * @param key    Redis key
     * @param value  value to store
     * @param ttl    expiry duration
     * @return a Mono emitting a boolean indicating success
     */
    public Mono<Boolean> setEx(String key, String value, Duration ttl) {
        if (!enabled()) return Mono.just(false);
        var body = List.of(List.of("SET", key, value, "EX", String.valueOf(ttl.toSeconds())));
        return pipeline(body)
                .map(list -> "OK".equalsIgnoreCase(value(list, 0)))
                .onErrorReturn(false);
    }

    /**
     * Increment a key and set its expiry simultaneously.  Useful for rate
     * limiting where both operations must be atomic.  When the client is
     * disabled, returns 0.  On error returns {@link Long#MAX_VALUE} to
     * represent an unbounded counter.
     *
     * @param key    Redis key
     * @param ttl    expiry duration
     * @return a Mono emitting the incremented value or Long.MAX_VALUE on error
     */
    public Mono<Long> incrExpire(String key, Duration ttl) {
        if (!enabled()) return Mono.just(0L);
        var body = List.of(
                List.of("INCR", key),
                List.of("EXPIRE", key, String.valueOf(ttl.toSeconds()))
        );
        return pipeline(body)
                .map(list -> {
                    var v = value(list, 0);
                    try {
                        return Long.parseLong(v);
                    } catch (Exception e) {
                        return Long.MAX_VALUE;
                    }
                })
                .onErrorReturn(Long.MAX_VALUE);
    }

    /**
     * Execute a pipeline of Redis commands.  This method constructs the
     * request payload and interprets the returned JSON.  Any parsing
     * exceptions are propagated.
     *
     * @param commands list of command lists
     * @return a Mono emitting the parsed response
     */
    private Mono<List<Map<String, Object>>> pipeline(List<?> commands) {
        return http.build()
                .post()
                .uri(url + "/pipeline")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(commands)
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> {
                    try {
                        return om.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * Extract the 'result' field from the pipeline response at the given index.
     *
     * @param list pipeline response list
     * @param idx  index to extract
     * @return the value as a string, or null when unavailable
     */
    private static String value(List<Map<String, Object>> list, int idx) {
        if (list == null || list.size() <= idx) return null;
        var v = list.get(idx).get("result");
        return v == null ? null : String.valueOf(v);
    }
}