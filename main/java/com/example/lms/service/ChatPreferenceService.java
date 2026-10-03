package com.example.lms.service;

import com.example.lms.domain.UserPreferenceProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Sparse, owner-scoped chat defaults in the existing profile document. Reads never create rows. */
@Service
public class ChatPreferenceService {
    public static final Set<String> KEYS = Set.of("model", "modelSelectionMode", "temperature", "topP",
            "frequencyPenalty", "presencePenalty", "maxTokens", "useRag", "useWebSearch", "searchMode", "ragAnswerPolicy");
    public record State(Map<String, Object> overrides, long revision, String hash) {}
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("revision_conflict"); }
    }
    @PersistenceContext private EntityManager entityManager;
    private final PlatformTransactionManager manager;
    private final ObjectMapper mapper;
    public ChatPreferenceService(PlatformTransactionManager manager, ObjectMapper mapper) {
        this.manager = manager; this.mapper = mapper;
    }
    private TransactionTemplate transaction(boolean readOnly) {
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(readOnly); tx.setTimeout(2);
        return tx;
    }
    private UserPreferenceProfile row(String owner, boolean lock) {
        if (owner == null || !owner.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("missing_owner");
        var query = entityManager.createQuery("select p from UserPreferenceProfile p where p.ownerKey = :owner",
                UserPreferenceProfile.class).setParameter("owner", owner).setMaxResults(1);
        if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", 1000);
        return query.getResultStream().findFirst().orElse(null);
    }
    public State read(String owner) {
        return transaction(true).execute(status -> state(document(row(owner, false))));
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> document(UserPreferenceProfile row) {
        if (row == null || row.getProfileMeta() == null || row.getProfileMeta().isBlank()) return new LinkedHashMap<>();
        try { return new LinkedHashMap<>(mapper.readValue(row.getProfileMeta(), Map.class)); }
        catch (Exception invalid) { throw new IllegalStateException("preferences_document_invalid"); }
    }
    @SuppressWarnings("unchecked")
    private State state(Map<String, Object> document) {
        if (!document.containsKey("chatDefaults")) return new State(Map.of(), 0, null);
        if (!(document.get("chatDefaults") instanceof Map<?, ?> namespace)
                || !Objects.equals(namespace.get("schemaVersion"), 1)
                || !(namespace.get("revision") instanceof Number revision) || revision.longValue() < 0
                || !(namespace.get("overrides") instanceof Map<?, ?> values))
            throw new IllegalStateException("preferences_document_invalid");
        Map<String, Object> clean = validate((Map<String, Object>) values);
        return new State(clean, revision.longValue(), hash(clean));
    }
    public State patch(String owner, Map<String, Object> set, List<String> unset, long revision, String expectedHash) {
        Map<String, Object> valid = validate(set == null ? Map.of() : set);
        List<String> removed = unset == null ? List.of() : List.copyOf(unset);
        if (removed.stream().anyMatch(key -> !KEYS.contains(key) || valid.containsKey(key)))
            throw new IllegalArgumentException("invalid_unset");
        try {
            return transaction(false).execute(status -> {
                var row = row(owner, true);
                var document = document(row);
                State current = state(document);
                if (current.revision() != revision || !Objects.equals(current.hash(), expectedHash)) throw new Conflict();
                Map<String, Object> next = new TreeMap<>(current.overrides());
                next.putAll(valid); removed.forEach(next::remove);
                validate(next); // Combined sparse values must be valid before the update commits.
                document.put("chatDefaults", Map.of("schemaVersion", 1, "revision", Math.addExact(revision, 1), "overrides", next));
                String json;
                try { json = mapper.writeValueAsString(document); }
                catch (Exception invalid) { throw new IllegalStateException("preferences_encode_failed"); }
                if (row == null) {
                    row = new UserPreferenceProfile(); row.setOwnerKey(owner);
                    row.setProfileMeta(json); entityManager.persist(row);
                } else row.setProfileMeta(json);
                entityManager.flush();
                return new State(Map.copyOf(next), revision + 1, hash(next));
            });
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) throw new Conflict();
            throw failure;
        }
    }
    public static Map<String, Object> validate(Map<String, Object> values) {
        if (values.size() > KEYS.size()) throw new IllegalArgumentException("unsupported_preference");
        Map<String, Object> clean = new TreeMap<>();
        for (var entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue(); boolean valid = false;
            if (value == null) throw new IllegalArgumentException("null_preference");
            if (!KEYS.contains(key)) throw new IllegalArgumentException("unsupported_preference");
            switch (key) {
                case "model" -> valid = value instanceof String text && text.length() <= 180
                        && text.matches("[a-zA-Z0-9][a-zA-Z0-9._/:+\\-]*") && !text.matches("^(sk-|AIza|eyJ).*");
                case "modelSelectionMode" -> valid = Set.of("preferred", "strict", "auto").contains(value);
                case "searchMode" -> valid = Set.of("AUTO", "OFF", "FORCE_LIGHT", "FORCE_DEEP").contains(value);
                case "ragAnswerPolicy" -> valid = Set.of("adaptive", "evidence_only").contains(value);
                case "useRag", "useWebSearch" -> valid = value instanceof Boolean;
                default -> {
                    if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                        double v = number.doubleValue();
                        valid = switch (key) {
                            case "temperature" -> v >= 0 && v <= 2;
                            case "topP" -> v >= 0 && v <= 1;
                            case "frequencyPenalty", "presencePenalty" -> v >= -2 && v <= 2;
                            case "maxTokens" -> v >= 1 && v <= Integer.MAX_VALUE && v == number.intValue();
                            default -> false;
                        };
                        if (valid) value = "maxTokens".equals(key) ? (Object) number.intValue() : number.doubleValue();
                    }
                }
            }
            if (!valid) throw new IllegalArgumentException("invalid_preference_" + key);
            clean.put(key, value);
        }
        if ("strict".equals(clean.get("modelSelectionMode")) && clean.get("model") instanceof String model
                && ("auto".equals(model) || model.startsWith("llmrouter.")))
            throw new IllegalArgumentException("strict_requires_concrete_model");
        return Collections.unmodifiableMap(clean);
    }
    private String hash(Map<String, Object> values) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(new TreeMap<>(values)).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException("preferences_hash_unavailable"); }
    }
}
