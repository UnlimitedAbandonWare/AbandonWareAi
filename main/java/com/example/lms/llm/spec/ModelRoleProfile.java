package com.example.lms.llm.spec;

import com.example.lms.llm.ModelCapabilities;
import com.example.lms.routing.RoutingProfile.Role;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Independent role measurements; catalog membership never authorizes a call. */
public record ModelRoleProfile(ModelKey modelKey, TierReference nominalTier, Set<Role> supportedRoles,
                               ModelCapabilities.Profile capabilities, String evidenceRef,
                               Readiness readiness, EvaluationVersion evaluationVersion,
                               Map<Role, Map<MetricName, Metric>> roleMetrics) {
    public enum Tier { EFFICIENT, GENERAL, REASONING, UNKNOWN }
    public enum Readiness { UNMEASURED, CANDIDATE, VALIDATED }
    public enum State { UNKNOWN, NOT_APPLICABLE, MEASURED, HISTORICAL }
    public enum MetricName { TASK_SUCCESS, SUPPORTED_CLAIM_RATE, VALID_CITATION_RATE, TOOL_CALL_SUCCESS,
                             SCHEMA_PASS_RATE, LATENCY_P50, LATENCY_P95, COST_PER_SUCCESS, TOTAL_COST }
    public record ModelKey(String provider, String modelId, String snapshot, String endpointKind, String adapterVersion) {
        public ModelKey {
            provider = identity(provider); modelId = identity(modelId);
            snapshot = optionalIdentity(snapshot); endpointKind = optionalIdentity(endpointKind);
            adapterVersion = optionalIdentity(adapterVersion);
        }
    }
    public record TierReference(Tier tier, String documentUrl, String checkedAt, boolean provisional) {
        public TierReference {
            tier = tier == null ? Tier.UNKNOWN : tier;
            if (documentUrl == null || !documentUrl.matches("https://[A-Za-z0-9./_?=&#%:+-]{1,400}")) documentUrl = null;
            if (checkedAt == null || !checkedAt.matches("\\d{4}-\\d{2}-\\d{2}")) checkedAt = null;
            provisional = provisional || documentUrl == null || checkedAt == null;
        }
    }
    public record EvaluationVersion(String datasetHash, String modelHash, String loadoutHash, String graderVersion) {
        public EvaluationVersion {
            datasetHash = identity(datasetHash); modelHash = identity(modelHash);
            loadoutHash = identity(loadoutHash); graderVersion = identity(graderVersion);
        }
    }
    public record Metric(State state, Double value, long samples, String unit, Instant measuredAt, EvaluationVersion version) {
        public Metric {
            Objects.requireNonNull(state);
            if (samples < 0) throw new IllegalArgumentException("NEGATIVE_SAMPLE_COUNT");
            unit = optionalIdentity(unit);
            if ((state == State.MEASURED || state == State.HISTORICAL)
                    && (value == null || !Double.isFinite(value) || value < 0 || samples == 0 || measuredAt == null || version == null)) {
                state = State.UNKNOWN;
            }
            if (state == State.UNKNOWN || state == State.NOT_APPLICABLE) value = null;
        }
        public static Metric unknown() { return new Metric(State.UNKNOWN, null, 0, "UNKNOWN", null, null); }
        public static Metric notApplicable() { return new Metric(State.NOT_APPLICABLE, null, 0, "N/A", null, null); }
        public static Metric rate(long numerator, long denominator, Instant at, EvaluationVersion version) {
            if (numerator < 0 || denominator < 0 || numerator > denominator) throw new IllegalArgumentException("INVALID_RATE_COUNTS");
            return denominator == 0 ? unknown() : new Metric(State.MEASURED, (double) numerator / denominator, denominator, "ratio", at, version);
        }
        public static Metric costPerSuccess(Double totalCost, long successes, Instant at, EvaluationVersion version) {
            if (successes <= 0 || totalCost == null) return unknown();
            return new Metric(State.MEASURED, totalCost / successes, successes, "USD/success", at, version);
        }
    }
    public record ValidationEvidence(boolean live, boolean privateHoldout, int rounds, int questionsPerRound,
                                     double taskSuccess, double supportedClaimRate, double validCitationRate,
                                     boolean toolAndSchemaPass, boolean securityPass, boolean budgetPass,
                                     boolean baselineNotWorse, EvaluationVersion version) { }

    public ModelRoleProfile {
        Objects.requireNonNull(modelKey);
        nominalTier = nominalTier == null ? new TierReference(Tier.UNKNOWN, null, null, true) : nominalTier;
        supportedRoles = supportedRoles == null ? Set.of() : Set.copyOf(supportedRoles);
        capabilities = capabilities == null ? ModelCapabilities.Profile.unknown() : capabilities;
        evidenceRef = optionalIdentity(evidenceRef);
        readiness = readiness == null ? Readiness.UNMEASURED : readiness;
        Map<Role, Map<MetricName, Metric>> immutable = new EnumMap<>(Role.class);
        if (roleMetrics != null) roleMetrics.forEach((role, metrics) -> immutable.put(role, Map.copyOf(metrics)));
        roleMetrics = Map.copyOf(immutable);
        // No public constructor can create a quality badge from claimed catalog/mock data.
        if (readiness == Readiness.VALIDATED) throw new IllegalArgumentException("VALIDATED_EVALUATION_HELD");
    }

    public static ModelRoleProfile fromCatalog(ModelSpecSnapshot snapshot) {
        Map<String, Object> m = snapshot.metadata();
        Tier tier;
        try { tier = Tier.valueOf(String.valueOf(m.getOrDefault("nominalTier", "UNKNOWN"))); }
        catch (IllegalArgumentException ex) { tier = Tier.UNKNOWN; }
        Set<Role> roles = new java.util.HashSet<>();
        if (m.get("supportedRoles") instanceof java.util.Collection<?> values) for (Object v : values) {
            try { roles.add(Role.valueOf(String.valueOf(v))); } catch (IllegalArgumentException ignored) { }
        }
        // Catalog capability labels are not adapter/endpoint verification.
        ModelCapabilities.Profile caps = ModelCapabilities.Profile.unknown();
        return new ModelRoleProfile(new ModelKey(snapshot.provider(), snapshot.model(), text(m, "modelSnapshot"),
                text(m, "apiSurface"), text(m, "adapterVersion")),
                new TierReference(tier, text(m, "sourceDocUrl"), text(m, "catalogCheckedAt"), true),
                roles, caps, "catalog", Readiness.UNMEASURED, null, Map.of());
    }

    /** Only the identical role and evaluation version are comparable. */
    public Metric metric(Role role, MetricName name) {
        Metric metric = roleMetrics.getOrDefault(role, Map.of()).get(name);
        if (metric == null || metric.state() == State.HISTORICAL) return Metric.unknown();
        if (metric.state() == State.MEASURED && !Objects.equals(evaluationVersion, metric.version())) return Metric.unknown();
        return metric;
    }

    public ModelRoleProfile atVersion(EvaluationVersion version) {
        if (Objects.equals(evaluationVersion, version)) return this;
        Map<Role, Map<MetricName, Metric>> history = new EnumMap<>(Role.class);
        roleMetrics.forEach((role, metrics) -> {
            Map<MetricName, Metric> rows = new EnumMap<>(MetricName.class);
            metrics.forEach((name, m) -> rows.put(name, m.state() == State.MEASURED
                    ? new Metric(State.HISTORICAL, m.value(), m.samples(), m.unit(), m.measuredAt(), m.version()) : m));
            history.put(role, rows);
        });
        return new ModelRoleProfile(modelKey, nominalTier, supportedRoles, capabilities, evidenceRef,
                Readiness.UNMEASURED, version, history);
    }

    /** Promotion stays held until the separately authorized private live evaluation is persisted. */
    public ModelRoleProfile validated(ValidationEvidence evidence) { return this; }

    public boolean meetsValidationThresholds(ValidationEvidence e) {
        return e != null && e.live() && e.privateHoldout() && e.rounds() >= 2 && e.questionsPerRound() >= 30
                && Objects.equals(evaluationVersion, e.version()) && e.taskSuccess() >= .90 && e.taskSuccess() <= 1
                && e.supportedClaimRate() >= .95 && e.supportedClaimRate() <= 1
                && e.validCitationRate() >= .95 && e.validCitationRate() <= 1
                && e.toolAndSchemaPass() && e.securityPass() && e.budgetPass() && e.baselineNotWorse();
    }
    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key); return value instanceof String s ? s : null;
    }
    private static String optionalIdentity(String value) { return value == null || value.isBlank() ? "UNKNOWN" : identity(value); }
    private static String identity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._/:+@-]{0,255}")
                || value.contains("://") || value.matches("(?i)^(sk-|Bearer|AIza|eyJ).*")) {
            throw new IllegalArgumentException("INVALID_PROFILE_IDENTITY");
        }
        return value;
    }
}
