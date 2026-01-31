package com.example.lms.service.vector;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * VectorSidService
 *
 * <p>Maintains a small persisted mapping from a logical sid (e.g. {@code __PRIVATE__})
 * to an active physical sid (e.g. {@code __PRIVATE__#...}).</p>
 *
 * <p>Rotation is used to hard-cut contaminated/global pools without having to delete
 * historical vectors. Searches should use only the active sid.</p>
 */
@Service
public class VectorSidService {

    private static final Logger log = LoggerFactory.getLogger(VectorSidService.class);

    public static final String QUARANTINE_SID = "Q";

    private final ObjectMapper om;

    @Value("${vector.sid.state-path:./data/vector-sid-state.json}")
    private String statePath;

    private volatile State state = new State();

    public VectorSidService(ObjectMapper om) {
        this.om = (om == null) ? new ObjectMapper() : om.copy();
        this.om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @PostConstruct
    public void init() {
        loadFailSoft();
    }

    /** Resolve the active sid for a logical sid (default: identity). */
    public String resolveActiveSid(String logicalSid) {
        String key = (logicalSid == null) ? "" : logicalSid.trim();
        if (key.isBlank()) return "";
        State s = state;
        String mapped = (s == null || s.mappings == null) ? null : s.mappings.get(key);
        return (mapped == null || mapped.isBlank()) ? key : mapped.trim();
    }

    /** Quarantine namespace sid. */
    public String quarantineSid() {
        return QUARANTINE_SID;
    }

    /** Rotate a logical sid to a new physical sid and persist state. */
    public synchronized String rotateSid(String logicalSid) {
        String key = (logicalSid == null) ? "" : logicalSid.trim();
        if (key.isBlank()) {
            throw new IllegalArgumentException("logicalSid is blank");
        }
        String prev = resolveActiveSid(key);

        // Short, URL-safe suffix.
        String suffix = Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 8);
        String next = key + "#" + suffix;

        State s = (state == null) ? new State() : state;
        if (s.mappings == null) s.mappings = new LinkedHashMap<>();
        s.mappings.put(key, next);
        s.updatedAt = Instant.now().toString();
        state = s;

        persistFailSoft();

        log.warn("[VectorSid] rotated logicalSid={} prev={} next={}", key, prev, next);
        return next;
    }

    /** Small diagnostic snapshot (no secrets). */
    public Map<String, Object> snapshot() {
        State s = state;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statePath", Objects.toString(statePath, ""));
        out.put("updatedAt", s == null ? null : s.updatedAt);
        out.put("mappings", s == null ? Map.of() : (s.mappings == null ? Map.of() : new LinkedHashMap<>(s.mappings)));
        out.put("quarantineSid", QUARANTINE_SID);
        return out;
    }

    /* ------------------------ persistence ------------------------ */

    private void loadFailSoft() {
        try {
            Path p = Path.of(statePath);
            if (!Files.exists(p)) {
                return;
            }
            String json = Files.readString(p, StandardCharsets.UTF_8);
            if (json == null || json.isBlank()) return;

            State s = om.readValue(json, State.class);
            if (s != null) {
                if (s.mappings == null) s.mappings = new LinkedHashMap<>();
                state = s;
            }
        } catch (Exception e) {
            log.debug("[VectorSid] load fail-soft: {}", e.toString());
        }
    }

    private void persistFailSoft() {
        try {
            Path p = Path.of(statePath);
            Path dir = p.getParent();
            if (dir != null) Files.createDirectories(dir);

            String json = om.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.debug("[VectorSid] persist fail-soft: {}", e.toString());
        }
    }


    /**
     * Build a "shadow" sid derived from an existing target sid.
     *
     * <p>We avoid changing the base of the sid (portion before '#') so that
     * global-only checks based on sidBase continue to behave sensibly.</p>
     */
    public String shadowSid(String targetSid, String runId) {
        String t = (targetSid == null) ? "" : targetSid.trim();
        if (t.isBlank()) return "#shadow-" + normalizeRunId(runId);

        String norm = normalizeRunId(runId);
        // If target already has a rotation suffix (contains '#'), append with a separator that doesn't
        // introduce another '#'. Otherwise, use a '#shadow-' suffix so sidBase remains stable.
        if (t.contains("#")) {
            return t + "~S" + norm;
        }
        return t + "#shadow-" + norm;
    }

    /**
     * Sanitize a run id so it is safe to embed inside sids / vector ids.
     */
    public static String normalizeRunId(String runId) {
        String r = (runId == null) ? "" : runId.trim();
        if (r.isBlank()) {
            r = Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        r = r.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (r.length() > 32) {
            r = r.substring(0, 32);
        }
        return r;
    }

    /* ------------------------ data ------------------------ */

    public static final class State {
        public Map<String, String> mappings = new LinkedHashMap<>();
        public String updatedAt = Instant.now().toString();
    }
}
