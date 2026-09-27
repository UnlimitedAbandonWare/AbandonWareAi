package com.example.lms.assist;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.core.env.Environment;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Cost admission for the existing cue caller. All counters are process-local, never account-balance claims. */
final class ConversateCueRoutingPolicy {
    record Demand(boolean gate, int quality, int inputTokens, int outputTokens, long latencyBudgetMs, double remainingUsd) {}
    record Choice(String key, double estimatedCost, double successRate, long expectedLatencyMs, String quotaState) {}
    record Reservation(Choice choice, String account, double reservedUsd, boolean verifiedFree) {}
    // Consecutive-failure cooldown steps; success resets the streak.
    private static final long[] COOLDOWN_MS={20000,60000,300000};
    private static final class Health {
        double latency, success = 1; long day = -1, requests, blockedUntil; int consecutiveFailures;
    }
    private static final class Account {
        long day = -1, requests, blockedUntil, snapshot = -1, snapshotRequests;
        double spent, snapshotSpent;
    }
    private final LlmRouterProperties routes;
    private final HybridLlmGatewayProbeService eligibility;
    private final Environment env;
    private final Clock clock;
    private final com.example.lms.agent.GroqFreeTierGuard groqGuard;
    private final Map<String, Health> health = new HashMap<>();
    private final Map<String, Account> accounts = new HashMap<>();

    ConversateCueRoutingPolicy(LlmRouterProperties routes, HybridLlmGatewayProbeService eligibility, Environment env, Clock clock) {
        this.routes = routes; this.eligibility = eligibility; this.env = env; this.clock = clock;
        this.groqGuard=new com.example.lms.agent.GroqFreeTierGuard(env,new com.fasterxml.jackson.databind.ObjectMapper(),clock);
    }

    synchronized List<Choice> candidates(Demand demand, Set<String> used) {
        return select(demand, used, null);
    }

    /** Bounded skip vocabulary so a future agent can see why a route never became a candidate. */
    private static void recordSkip(List<Map<String,Object>> skipped, String key, String reason) {
        if (skipped != null) skipped.add(Map.of("route", key, "reason", reason));
    }

    private List<Choice> select(Demand demand, Set<String> used, List<Map<String,Object>> skipped) {
        boolean enforceCost=env.getProperty("conversate.cost.enforce-limits",Boolean.class,true);
        if (!routes.isEnabled() || demand.latencyBudgetMs() <= 0 || enforceCost&&demand.remainingUsd() < 0) return List.of();
        var choices = new ArrayList<Choice>();
        for (var entry : routes.getModels().entrySet()) {
            String key = entry.getKey(); var cfg = entry.getValue();
            if (cfg == null || !cfg.isEnabled() || cfg.getName() == null || cfg.getProvider() == null
                    || !"chat".equalsIgnoreCase(cfg.getStage()) || local(cfg)
                    || cfg.getCredentialEnv() == null || ConfigValueGuards.isMissing(env.getProperty(cfg.getCredentialEnv()))) {
                recordSkip(skipped, key, "route_ineligible_or_credential_missing"); continue;
            }
            if (used.contains(key)
                    || used.stream().map(routes.getModels()::get).filter(Objects::nonNull).anyMatch(c -> identity(c).equals(identity(cfg)))) {
                recordSkip(skipped, key, "already_attempted_or_excluded"); continue;
            }
            String p = prefix(key);
            // Groq's free-only requirement is independent of observational dollar budgets.
            boolean groq="groq".equalsIgnoreCase(cfg.getProvider());
            if (groq && !groqGuard.eligible(cfg.getName(),env.getProperty(cfg.getCredentialEnv()))) {
                recordSkip(skipped, key, "groq_free_tier_unverified"); continue;
            }
            int quality = (int) number(p+"quality", 1);
            int maximum = demand.gate() ? 2 : demand.quality() <= 2 ? 2 : demand.quality();
            if (quality < demand.quality() || quality > maximum
                    || demand.gate() && !env.getProperty(p+"gate", Boolean.class, false)) {
                recordSkip(skipped, key, "quality_or_gate_requirement"); continue;
            }
            String pricedModel = env.getProperty(p+"priced-model", cfg.getName());
            if (!cfg.getName().equals(pricedModel) || !env.containsProperty(p+"input-usd-per-million")
                    || !env.containsProperty(p+"output-usd-per-million")) {
                recordSkip(skipped, key, "priced_model_or_pricing_missing"); continue;
            }
            if (demand.inputTokens()+demand.outputTokens() > number(p+"context-tokens", 32000)) {
                recordSkip(skipped, key, "demand_exceeds_context_tokens"); continue;
            }
            // Gemini's router is separately opt-in. Metadata/catalog presence does not enable generation.
            if ("gemini".equalsIgnoreCase(cfg.getProvider()) && (!env.getProperty("gemini.gateway.enabled", Boolean.class, true)
                    || !env.getProperty("gemini.gateway.purpose.router.enabled", Boolean.class, false))) {
                recordSkip(skipped, key, "gemini_router_not_enabled"); continue;
            }
            Health h = health(key); Account a = account(cfg);
            if (h.blockedUntil > clock.millis() || a.blockedUntil > clock.millis()
                    || h.requests >= number(p+"daily-request-limit", 1000)) {
                recordSkip(skipped, key, "cooldown_or_daily_limit"); continue;
            }
            long latency = Math.round(h.latency > 0 ? h.latency : number(p+"expected-latency-ms", 1500));
            if (latency > demand.latencyBudgetMs()) {
                recordSkip(skipped, key, "expected_latency_over_budget"); continue;
            }
            double estimate = estimate(key, demand.inputTokens(), demand.outputTokens());
            String quota = quotaPrefix(cfg); boolean fresh = snapshotFresh(quota);
            boolean free = groq || fresh && a.snapshotRequests < number(quota+"verified-free-requests-remaining", 0);
            double charged = free ? 0 : estimate;
            if (enforceCost&&(charged > demand.remainingUsd() || a.spent+charged > number(quota+"daily-budget-usd", .50)
                    || fresh && env.containsProperty(quota+"verified-balance-usd")
                    && a.snapshotSpent+charged > number(quota+"verified-balance-usd", 0))) {
                recordSkip(skipped, key, "cost_limit_exceeded"); continue;
            }
            if (fresh && env.containsProperty(quota+"verified-requests-remaining")
                    && a.snapshotRequests >= number(quota+"verified-requests-remaining", 0)) {
                recordSkip(skipped, key, "verified_requests_exhausted"); continue;
            }
            var result = eligibility.evaluate(key, cfg, "chat");
            if (result == null || !result.eligible()) {
                recordSkip(skipped, key, "endpoint_not_eligible"); continue;
            }
            choices.add(new Choice(key, charged, h.success, latency, groq?"free_only_wire_admission_required":free ? "verified_free_snapshot" : fresh ? "verified_snapshot" : "unknown"));
        }
        // Fast classification must not time out and cool down the primary hint routes.
        // Primary-provider preference applies to final answers; quality remains an admission constraint.
        // Real-time lane: weight observed latency into the efficiency key, so a slow or
        // flaky route cannot stay first only because its token price is marginally lower.
        choices.sort(Comparator.<Choice>comparingInt(c -> demand.gate() || primary(routes.getModels().get(c.key())) ? 0 : 1)
                .thenComparingDouble(c -> c.estimatedCost()*c.expectedLatencyMs()/Math.max(.1, c.successRate()))
                .thenComparingLong(Choice::expectedLatencyMs).thenComparing(Choice::key));
        var identities = new HashSet<String>();
        return choices.stream().filter(c -> identities.add(identity(routes.getModels().get(c.key())))).toList();
    }

    /** Selection and reservation share one monitor: parallel cues cannot spend the same allowance twice. */
    synchronized Reservation reserve(Demand demand, Set<String> used) {
        return reserve(demand, used, null);
    }

    /** When skipped is non-null it receives the per-route exclusion reasons of this evaluation. */
    synchronized Reservation reserve(Demand demand, Set<String> used, List<Map<String,Object>> skipped) {
        var choices = select(demand, used, skipped); if (choices.isEmpty()) return null;
        Choice choice = choices.get(0); var cfg = routes.getModels().get(choice.key());
        Health h = health(choice.key()); Account a = account(cfg);
        h.requests++; a.requests++; a.snapshotRequests++;
        a.spent += choice.estimatedCost(); a.snapshotSpent += choice.estimatedCost();
        return new Reservation(choice, accountKey(cfg), choice.estimatedCost(), Set.of("verified_free_snapshot","free_only_wire_admission_required").contains(choice.quotaState()));
    }

    synchronized void complete(Reservation reservation, boolean success, long latencyMs, String failure, TokenUsage usage) {
        Health h = health(reservation.choice().key());
        h.latency = h.latency == 0 ? latencyMs : .7*h.latency+.3*latencyMs;
        if (!"CANCELLED".equals(failure)) h.success = .7*h.success+.3*(success ? 1 : 0);
        if (success) h.consecutiveFailures = 0;
        else if (!"CANCELLED".equals(failure)) {
            int step = Math.min(COOLDOWN_MS.length-1, h.consecutiveFailures++);
            if (Set.of("GENERATION_RATE_LIMITED","API_QUOTA_EXHAUSTED").contains(failure)) step = Math.max(1, step);
            h.blockedUntil = clock.millis()+COOLDOWN_MS[step];
        }
        if (Set.of("GENERATION_DENIED","API_QUOTA_EXHAUSTED").contains(failure))
            accounts.get(reservation.account()).blockedUntil = clock.millis()+300000;
        // Missing or partial usage, including a timeout, retains its conservative reservation.
        if (!reservation.verifiedFree() && usage != null && usage.inputTokenCount() != null && usage.inputTokenCount() > 0
                && usage.outputTokenCount() != null && usage.outputTokenCount() >= 0) {
            double observed = estimate(reservation.choice().key(), usage.inputTokenCount(), usage.outputTokenCount());
            Account a = accounts.get(reservation.account());
            double adjustment = observed-reservation.reservedUsd();
            a.spent = Math.max(0, a.spent+adjustment); a.snapshotSpent = Math.max(0, a.snapshotSpent+adjustment);
        }
    }

    /** Diagnostics-only snapshot of tried routes: cooldown, streak, EWMA signals. No credentials. */
    synchronized List<Map<String, Object>> healthSnapshot() {
        var rows = new ArrayList<Map<String, Object>>();
        long now = clock.millis();
        for (var entry : health.entrySet()) {
            var cfg = routes.getModels().get(entry.getKey()); var h = entry.getValue();
            if (cfg == null) continue;
            var row = new LinkedHashMap<String, Object>();
            row.put("route", entry.getKey());
            row.put("provider", cfg.getProvider()); row.put("model", cfg.getName());
            row.put("consecutiveFailures", h.consecutiveFailures);
            row.put("cooldownActive", h.blockedUntil > now);
            row.put("cooldownUntilMs", h.blockedUntil > now ? h.blockedUntil : 0L);
            row.put("successRate", Math.round(h.success * 1000) / 1000.0);
            row.put("ewmaLatencyMs", Math.round(h.latency));
            rows.add(row);
        }
        return rows;
    }

    double estimate(String key, int inputTokens, int outputTokens) {
        var cfg = routes.getModels().get(key); String p = prefix(key);
        double input = number(p+"input-usd-per-million", 10), output = number(p+"output-usd-per-million", 30);
        // Published 3.8 Flash promotional end date; avoid silently retaining its half price in 2027.
        if (cfg != null && "gemini-3.8-flash".equals(cfg.getName()) && clock.instant().isAfter(Instant.parse("2026-12-31T23:59:59Z"))) {
            input = Math.max(input, 1.50); output = Math.max(output, 7.50);
        }
        // No cache hit is assumed before a response. Reserve potential GPT cache-write pricing too.
        double writeFactor = cfg != null && "openai".equalsIgnoreCase(cfg.getProvider()) ? 1.25 : 1;
        return (Math.max(0,inputTokens)*input*writeFactor+Math.max(0,outputTokens)*output)/1_000_000d;
    }
    private Health health(String key) {
        Health h = health.computeIfAbsent(key,k -> new Health()); long day = clock.millis()/86400000;
        if (h.day != day) { h.day=day; h.requests=0; } return h;
    }
    private Account account(LlmRouterProperties.ModelConfig cfg) {
        Account a = accounts.computeIfAbsent(accountKey(cfg),k -> new Account()); long day=clock.millis()/86400000;
        if (a.day != day) { a.day=day; a.requests=0; a.spent=0; }
        long snapshot=(long)number(quotaPrefix(cfg)+"observed-at-ms",0);
        if (a.snapshot != snapshot) { a.snapshot=snapshot; a.snapshotRequests=0; a.snapshotSpent=0; } return a;
    }
    private boolean snapshotFresh(String prefix) {
        long at=(long)number(prefix+"observed-at-ms",0), age=clock.millis()-at;
        return at>0 && age>=0 && age<=Math.min(300000,number(prefix+"ttl-ms",300000));
    }
    private double number(String key, double fallback) {
        Double v=env.getProperty(key,Double.class,fallback); return v!=null&&Double.isFinite(v)&&v>=0 ? v : fallback;
    }
    private static String prefix(String key) { return "conversate.cue.routes."+key+"."; }
    private static String quotaPrefix(LlmRouterProperties.ModelConfig cfg) { return "conversate.cue.accounts."+cfg.getProvider().toLowerCase(Locale.ROOT)+"."; }
    // Provider-level quota settings must share counters even when keys have different environment aliases.
    private static String accountKey(LlmRouterProperties.ModelConfig cfg) { return cfg.getProvider().toLowerCase(Locale.ROOT); }
    private static String identity(LlmRouterProperties.ModelConfig cfg) { return accountKey(cfg)+":"+cfg.getBaseUrl()+":"+cfg.getName(); }
    private static boolean primary(LlmRouterProperties.ModelConfig cfg) { return Set.of("openai","gemini").contains(cfg.getProvider().toLowerCase(Locale.ROOT)); }
    private static boolean local(LlmRouterProperties.ModelConfig cfg) { return Set.of("local","ollama","local-openai").contains(cfg.getProvider().toLowerCase(Locale.ROOT)); }
}
