package com.example.lms.infra.upstash;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
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

    @Value("${upstash.redis.rest-url:${UPSTASH_REDIS_REST_URL:}}")
    private String url;
    @Value("${upstash.redis.rest-token:${UPSTASH_REDIS_REST_TOKEN:}}")
    private String token;

    /**
     * Determine whether the client is properly configured.  The client is
     * considered enabled when both the REST URL and token are non-blank.
     *
     * @return {@code true} when ready for use
     */
    public boolean enabled() {
        return configurationFailure() == null;
    }

    private String configurationFailure() {
        if (ConfigValueGuards.isMissing(url)) return "missing_url";
        if (ConfigValueGuards.isMissing(token)) return "missing_token";
        try {
            var endpoint = java.net.URI.create(url.trim());
            String host = endpoint.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                    || "[::1]".equals(host) || "::1".equals(host);
            if (host == null || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null || !("https".equalsIgnoreCase(endpoint.getScheme())
                    || (loopback && "http".equalsIgnoreCase(endpoint.getScheme())))) return "invalid_url";
            return null;
        } catch (IllegalArgumentException invalid) { return "invalid_url"; }
    }

    /** Strict atomic command for admission: unavailable Redis must never mean allowed. */
    public Mono<List<Long>> eval(String script, List<String> keys, List<String> arguments) {
        return Mono.defer(() -> {
        long began = System.nanoTime();
        String missing = configurationFailure();
        if (missing != null) return Mono.error(admissionFailure(new IllegalStateException(missing), began));
        var command = new java.util.ArrayList<String>();
        command.add("EVAL"); command.add(script); command.add(Integer.toString(keys.size()));
        command.addAll(keys); command.addAll(arguments);
        return pipeline(List.of(command)).timeout(Duration.ofSeconds(2)).map(rows -> {
            if (rows.size() != 1 || rows.get(0).containsKey("error")
                    || !(rows.get(0).get("result") instanceof List<?> values))
                throw new IllegalStateException("redis_admission_invalid_reply");
            var result = new java.util.ArrayList<Long>();
            for (Object value : values) {
                if (!(value instanceof Integer || value instanceof Long)) throw new IllegalStateException("redis_admission_invalid_reply");
                Number number = (Number) value;
                result.add(number.longValue());
            }
            return List.copyOf(result);
        }).onErrorMap(error -> admissionFailure(error, began));
        });
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
        return batch(body, "/multi-exec")
                .map(list -> {
                    if (!"1".equals(value(list, 1))) {
                        throw new IllegalStateException("redis_expire_unsuccessful");
                    }
                    var v = value(list, 0);
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        traceSuppressed("incrParse", e);
                        return Long.MAX_VALUE;
                    }
                })
                .doOnError(e -> traceSuppressed("incrExpire", e))
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
        return batch(commands, "/pipeline");
    }

    private Mono<List<Map<String, Object>>> batch(List<?> commands, String endpoint) {
        return http.build()
                .post()
                .uri(url.trim() + endpoint)
                .header("Authorization", "Bearer " + token.trim())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(commands)
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> {
                    try {
                        List<Map<String, Object>> rows = om.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                        if (rows == null || rows.size() != commands.size()) throw new IllegalStateException("redis_admission_invalid_reply");
                        for (var row : rows) {
                            if (row == null) throw new IllegalStateException("redis_admission_invalid_reply");
                            if (row.containsKey("error")) {
                                String error = String.valueOf(row.get("error")).toUpperCase(java.util.Locale.ROOT);
                                String reason = error.contains("NOPERM") || error.contains("READONLY") ? "permission_denied"
                                        : error.contains("WRONGPASS") || error.contains("NOAUTH") ? "authentication_failed" : "upstream_command_error";
                                throw new IllegalStateException(reason);
                            }
                            if (!row.containsKey("result")) throw new IllegalStateException("redis_admission_invalid_reply");
                        }
                        return rows;
                    } catch (IllegalStateException classified) {
                        throw classified;
                    } catch (Exception e) {
                        throw new IllegalStateException("redis_admission_invalid_reply");
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

    private static IllegalStateException admissionFailure(Throwable failure, long began) {
        String reason;
        Integer status = null;
        if (failure instanceof org.springframework.web.reactive.function.client.WebClientResponseException response) {
            status = response.getStatusCode().value();
            reason = status == 401 ? "authentication_failed" : status == 403 ? "permission_denied"
                    : status == 429 ? "provider_rate_limited" : "upstream_http_error";
        } else if (failure instanceof java.util.concurrent.TimeoutException) reason = "timeout";
        else if (failure instanceof org.springframework.web.reactive.function.client.WebClientRequestException) reason = "network_error";
        else reason = switch (java.util.Objects.toString(failure.getMessage(), "")) {
            case "missing_url", "missing_token", "invalid_url", "permission_denied", "authentication_failed", "upstream_command_error" -> failure.getMessage();
            case "redis_admission_invalid_reply" -> "invalid_response";
            default -> "unavailable";
        };
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        TraceStore.put("upstash.redis.admission.reasonCode", reason);
        TraceStore.put("upstash.redis.admission.upstreamStatus", status == null ? "not_observed" : status);
        TraceStore.put("upstash.redis.admission.elapsedMs", elapsedMs);
        TraceStore.put("upstash.redis.admission.retryCount", 0);
        org.slf4j.LoggerFactory.getLogger(UpstashRedisClient.class).warn(
                "redis.admission result=unavailable reasonCode={} upstreamStatus={} elapsedMs={} retryCount=0", reason, status, elapsedMs);
        return new IllegalStateException("redis_admission_unavailable");
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
        TraceStore.put("upstash.redis.suppressed.stage", safeStage);
        TraceStore.put("upstash.redis.suppressed.errorType", errorType);
        TraceStore.put("upstash.redis.suppressed." + safeStage, true);
        TraceStore.put("upstash.redis.suppressed." + safeStage + ".errorType", errorType);
    }
}
