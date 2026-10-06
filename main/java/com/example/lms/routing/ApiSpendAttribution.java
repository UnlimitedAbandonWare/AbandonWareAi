package com.example.lms.routing;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Secret-safe API spend attribution for agent/dev sessions and production observability.
 * Never pass raw API keys into these methods.
 */
public final class ApiSpendAttribution {

    private static final System.Logger LOG = System.getLogger(ApiSpendAttribution.class.getName());
    private static final ConcurrentHashMap<String, String> SUCCESS_CACHE = new ConcurrentHashMap<>();

    private ApiSpendAttribution() {
    }

    public static boolean agentModeActive() {
        return agentModeActive(new org.springframework.core.env.StandardEnvironment());
    }

    public static boolean agentModeActive(org.springframework.core.env.Environment environment) {
        return new ApiRoutingPolicySnapshot(environment).agentModeActive();
    }

    public static boolean shouldSkipSuccessfulReplay(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        return SUCCESS_CACHE.containsKey(fingerprint);
    }

    public static void markSuccess(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        SUCCESS_CACHE.put(fingerprint, "ok");
    }

    public static boolean isStalePaidAutoModel(String model) {
        var policy = new ApiRoutingPolicySnapshot(new org.springframework.core.env.StandardEnvironment());
        return policy.agentModeActive() && !policy.explicitPaidOverride() && policy.staleAutoModel(model);
    }

    public static void record(
            String purpose,
            String provider,
            String model,
            String tier,
            String why,
            String caller,
            String cache,
            Integer httpStatus,
            String errorClass,
            Integer promptTokens,
            Integer completionTokens,
            String estCostClass) {
        if (!LOG.isLoggable(System.Logger.Level.INFO)) {
            return;
        }
        LOG.log(
                System.Logger.Level.INFO,
                "[AWX][api-spend] session={0} agentMode={1} purpose={2} provider={3} model={4} tier={5} why={6} caller={7} cache={8} httpStatus={9} errorClass={10} promptTokens={11} completionTokens={12} estCostClass={13}",
                sessionId(),
                agentModeActive(),
                safe(purpose),
                safe(provider),
                safeModel(model),
                safe(tier),
                safe(why),
                safe(caller),
                safe(cache),
                httpStatus == null ? "n/a" : httpStatus,
                safe(errorClass == null ? "n/a" : errorClass),
                promptTokens == null ? "n/a" : promptTokens,
                completionTokens == null ? "n/a" : completionTokens,
                safe(estCostClass));
    }

    /** Payload-free local observation, not the provider account-wide billing meter. Never an admission gate. */
    public static synchronized GroundingUsage recordGrounding(java.nio.file.Path path, java.time.YearMonth month,
            int queryCount, long monthlyAllowance) {
        long allowance = Math.max(1, monthlyAllowance);
        try {
            var target = path.toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(target.getParent());
            try (var channel = java.nio.channels.FileChannel.open(target, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null || channel.size() > 1024) throw new java.io.IOException("usage_ledger_unavailable");
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                long previous = 0;
                if (channel.size() > 0) {
                    var buffer = java.nio.ByteBuffer.allocate((int)channel.size());
                    while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
                    var saved = mapper.readTree(buffer.array());
                    if (!saved.path("month").isTextual() || !saved.path("observedQueries").isIntegralNumber()
                            || saved.path("observedQueries").asLong(-1) < 0) throw new java.io.IOException("invalid_usage_ledger");
                    if (month.toString().equals(saved.path("month").asText())) previous = saved.path("observedQueries").asLong();
                }
                long observed = Math.addExact(previous, Math.max(0, queryCount));
                byte[] bytes = mapper.writeValueAsBytes(java.util.Map.of("month", month.toString(), "observedQueries", observed));
                channel.position(0);channel.truncate(0);var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(false);
                boolean near = observed >= Math.ceil(allowance * .9d);
                LOG.log(near ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                        "[AWX][api-spend] purpose=gemini-grounding month={0} observedQueries={1} requestQueries={2} monthlyAllowance={3} nearAllowance={4} meter=local_observed lowerBound=true admission=unchanged",
                        month, observed, Math.max(0,queryCount), allowance, near);
                return new GroundingUsage(month.toString(), observed, near, true);
            }
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "[AWX][api-spend] purpose=gemini-grounding meter=unavailable admission=unchanged");
            return new GroundingUsage(month.toString(), null, false, false);
        }
    }
    public record GroundingUsage(String month, Long observedQueries, boolean nearAllowance, boolean accountingAvailable) {}

    public static String fingerprint(String purpose, String provider, String model, String caller, String probeId) {
        return String.join("|",
                Objects.toString(purpose, ""),
                Objects.toString(provider, ""),
                Objects.toString(model, ""),
                Objects.toString(caller, ""),
                Objects.toString(probeId, ""));
    }

    private static String sessionId() {
        String s = System.getenv("AWX_AGENT_SESSION");
        if (notBlank(s)) {
            return s.trim();
        }
        String host = System.getenv("AWX_AGENT_HOST");
        if (notBlank(host)) {
            return "agent-host:" + host.trim();
        }
        return "default";
    }

    private static String safeModel(String model) {
        if (model == null || model.isBlank()) {
            return "n/a";
        }
        // models are labels; still truncate pathological values
        String t = model.trim();
        return t.length() > 64 ? t.substring(0, 64) + "…" : t;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        String t = value.trim();
        if (t.length() > 96) {
            return t.substring(0, 24) + "…len=" + t.length();
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sk-") || lower.startsWith("gsk_") || lower.startsWith("bearer ")) {
            return "redacted_len=" + t.length();
        }
        return t;
    }

    private static boolean truthy(String v) {
        if (v == null) {
            return false;
        }
        String t = v.trim().toLowerCase(Locale.ROOT);
        return t.equals("1") || t.equals("true") || t.equals("yes") || t.equals("on");
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
