package com.example.lms.service.rag.offline;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class OfflineTextureSnapshotLoader {
    private static final Logger log = LoggerFactory.getLogger(OfflineTextureSnapshotLoader.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final OfflineTextureProperties properties;
    private final ObjectMapper objectMapper;

    public OfflineTextureSnapshotLoader(OfflineTextureProperties properties, ObjectMapper objectMapper) {
        this.properties = properties == null ? new OfflineTextureProperties() : properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public TextureLookup lookup(String query, List<String> anchors) {
        return lookup(query, anchors, looksFreshnessQuery(query));
    }

    public TextureLookup lookup(String query, List<String> anchors, boolean freshnessQuery) {
        if (!properties.isEnabled()) {
            TextureLookup disabled = TextureLookup.disabled("disabled");
            trace(disabled);
            return disabled;
        }
        Path manifest = Path.of(properties.getManifestPath());
        if (!Files.exists(manifest)) {
            TextureLookup cold = new TextureLookup(0, 0, 0, 0.0d, true, false, freshnessQuery,
                    "offline_texture_missing", List.of());
            trace(cold);
            return cold;
        }

        List<OfflineTextureSnapshot> loaded = new ArrayList<>();
        int stale = 0;
        try {
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            int max = Math.max(1, properties.getMaxSnapshots());
            int from = Math.max(0, lines.size() - max);
            for (int i = from; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null || line.isBlank()) {
                    continue;
                }
                OfflineTextureSnapshot snapshot = readSnapshot(line);
                if (snapshot == null) {
                    continue;
                }
                if (isExpired(snapshot)) {
                    stale++;
                    continue;
                }
                loaded.add(snapshot);
            }
        } catch (Exception e) {
            log.debug("[OfflineTexture] fail-soft stage=read.manifest err=read-failure");
            TextureLookup failed = new TextureLookup(0, 0, 0, 0.0d, true, false, freshnessQuery,
                    "offline_texture_read_error", List.of());
            trace(failed);
            return failed;
        }

        Set<String> anchorKeys = anchorKeys(anchors);
        List<OfflineTextureSnapshot> matches = new ArrayList<>();
        for (OfflineTextureSnapshot snapshot : loaded) {
            if (matches(snapshot, anchorKeys)) {
                matches.add(snapshot);
            }
        }
        double hitRate = loaded.isEmpty() ? 0.0d : matches.size() / (double) loaded.size();
        boolean staleOnly = loaded.isEmpty() && stale > 0;
        boolean staleQualitySignal = staleOnly && freshnessQuery;
        String reason = matches.isEmpty()
                ? (staleQualitySignal ? "offline_texture_stale"
                        : staleOnly ? "offline_texture_expired_ignored" : "offline_texture_cold")
                : "offline_texture_hit";
        TextureLookup out = new TextureLookup(loaded.size(), matches.size(), stale, round4(hitRate),
                loaded.isEmpty(), staleQualitySignal, staleQualitySignal, reason, matches);
        trace(out);
        return out;
    }

    private OfflineTextureSnapshot readSnapshot(String manifestLine) throws Exception {
        Map<String, Object> row = objectMapper.readValue(manifestLine, MAP_TYPE);
        Object pathValue = row.get("snapshotPath");
        if (pathValue instanceof String path && !path.isBlank()) {
            Path snapshotPath = Path.of(path);
            if (!snapshotPath.isAbsolute()) {
                Path manifestParent = Path.of(properties.getManifestPath()).getParent();
                snapshotPath = manifestParent == null ? snapshotPath : manifestParent.resolve(snapshotPath).normalize();
            }
            if (Files.exists(snapshotPath)) {
                return objectMapper.readValue(Files.readString(snapshotPath, StandardCharsets.UTF_8),
                        OfflineTextureSnapshot.class);
            }
        }
        if (row.containsKey("snapshotId") && row.containsKey("anchors")) {
            return objectMapper.convertValue(row, OfflineTextureSnapshot.class);
        }
        return null;
    }

    private static boolean matches(OfflineTextureSnapshot snapshot, Set<String> anchorKeys) {
        if (snapshot == null || anchorKeys == null || anchorKeys.isEmpty()) {
            return false;
        }
        for (String anchor : snapshot.anchors()) {
            String normalized = normalize(anchor);
            String hash = SafeRedactor.hash12(anchor);
            if (anchorKeys.contains(normalized) || (hash != null && anchorKeys.contains(hash))) {
                return true;
            }
        }
        for (String entity : snapshot.entityKeys()) {
            String normalized = normalize(entity);
            String hash = SafeRedactor.hash12(entity);
            if (anchorKeys.contains(normalized) || (hash != null && anchorKeys.contains(hash))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> anchorKeys(List<String> anchors) {
        Set<String> keys = new LinkedHashSet<>();
        if (anchors == null) {
            return keys;
        }
        for (String anchor : anchors) {
            if (anchor == null || anchor.isBlank()) {
                continue;
            }
            keys.add(normalize(anchor));
            String hash = SafeRedactor.hash12(anchor);
            if (hash != null && !hash.isBlank()) {
                keys.add(hash);
            }
        }
        return keys;
    }

    private static boolean isExpired(OfflineTextureSnapshot snapshot) {
        if (snapshot == null || snapshot.expiresAt() == null || snapshot.expiresAt().isBlank()) {
            return false;
        }
        try {
            return Instant.parse(snapshot.expiresAt()).isBefore(Instant.now());
        } catch (Exception ignore) {
            log.debug("[OfflineTexture] fail-soft stage=expiry.parse err=parse-failure"); return false;
        }
    }

    public static boolean looksFreshnessQuery(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return q.contains("latest")
                || q.contains("recent")
                || q.contains("today")
                || q.contains("news")
                || q.contains("2026")
                || q.contains("최신")
                || q.contains("최근")
                || q.contains("오늘")
                || q.contains("뉴스")
                || q.contains("발표");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static double round4(double value) {
        double safe = Double.isNaN(value) || Double.isInfinite(value) ? 0.0d : value;
        return Math.round(Math.max(0.0d, Math.min(1.0d, safe)) * 10000.0d) / 10000.0d;
    }

    private static void trace(TextureLookup lookup) {
        try {
            TraceStore.put("rag.offlineTexture.enabled", !"disabled".equals(lookup.reason()));
            TraceStore.put("rag.offlineTexture.loadedSnapshots", lookup.loadedSnapshots());
            TraceStore.put("rag.offlineTexture.matchedSnapshots", lookup.matchedSnapshots());
            TraceStore.put("rag.offlineTexture.staleSnapshots", lookup.staleSnapshots());
            TraceStore.put("rag.offlineTexture.hitRate", lookup.hitRate());
            TraceStore.put("rag.offlineTexture.coldStart", lookup.coldStart());
            TraceStore.put("rag.offlineTexture.stale", lookup.stale());
            TraceStore.put("rag.offlineTexture.onlineSearchNeeded", lookup.onlineSearchNeeded());
            TraceStore.put("rag.offlineTexture.reason", lookup.reason());
            TraceStore.append("orch.events.v1", Map.of(
                    "type", "OFFLINE_TEXTURE_LOADED",
                    "matchedSnapshots", lookup.matchedSnapshots(),
                    "loadedSnapshots", lookup.loadedSnapshots(),
                    "reason", lookup.reason()));
        } catch (Throwable ignore) {
            log.debug("[OfflineTexture] fail-soft stage=lookup.trace err=trace-failure");
        }
    }

    public record TextureLookup(
            int loadedSnapshots,
            int matchedSnapshots,
            int staleSnapshots,
            double hitRate,
            boolean coldStart,
            boolean stale,
            boolean onlineSearchNeeded,
            String reason,
            List<OfflineTextureSnapshot> matches
    ) {
        public TextureLookup {
            loadedSnapshots = Math.max(0, loadedSnapshots);
            matchedSnapshots = Math.max(0, matchedSnapshots);
            staleSnapshots = Math.max(0, staleSnapshots);
            hitRate = round4(hitRate);
            reason = reason == null ? "" : reason;
            matches = matches == null ? List.of() : List.copyOf(matches);
        }

        public static TextureLookup disabled(String reason) {
            return new TextureLookup(0, 0, 0, 0.0d, true, false, false, reason, List.of());
        }
    }
}
