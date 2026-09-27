package com.example.lms.service.rag.graph;

import com.example.lms.assist.MemoryEvidence;
import com.example.lms.service.VectorMetaKeys;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Optional;

/** Source/policy fence shared by the queue, delayed redrive and prompt entry. */
@Service
public class GeneralGraphVectorGate {
    private static final String PREFIX = "general_graph_";
    private final GeneralGraphSourceAuthority authority;
    public GeneralGraphVectorGate(GeneralGraphSourceAuthority authority) { this.authority = authority; }

    public static boolean requiresGate(Map<String, Object> meta) {
        if (meta == null) return false;
        String type = value(meta, VectorMetaKeys.META_DOC_TYPE);
        return meta.keySet().stream().anyMatch(k -> k.startsWith(PREFIX))
                || "BRAIN_STATE".equalsIgnoreCase(type) || "GRAPHDB_MANUAL_LEARNING".equalsIgnoreCase(type)
                || meta.containsKey("kb_confidence")
                || "brain_state".equals(value(meta, "ingest_lane"))
                || "graphdb_manual_learning".equals(value(meta, "ingest_lane"));
    }

    private static boolean publicManual(Map<String, Object> meta) {
        return meta != null && meta.keySet().stream().noneMatch(k -> k.startsWith(PREFIX))
                && "GRAPHDB_MANUAL_LEARNING".equals(value(meta, VectorMetaKeys.META_DOC_TYPE))
                && "graphdb_manual_learning".equals(value(meta, "ingest_lane"));
    }

    public static boolean commit(GeneralGraphVectorGate gate, String sid, Map<String, Object> meta, Runnable write) {
        if (!requiresGate(meta) || publicManual(meta)) {
            write.run();
            return true;
        }
        return gate != null && gate.commitPrivate(sid, meta, write);
    }

    private boolean commitPrivate(String sid, Map<String, Object> meta, Runnable write) {
        GeneralGraphScope scope = claim(meta);
        if (scope == null || sid == null
                || !(sid.equals(Long.toString(scope.sessionId()))
                     || sid.startsWith(scope.sessionId() + "#") || sid.startsWith(scope.sessionId() + "~")))
            return false;
        var source = current(scope, meta);
        if (source.isEmpty()) return false;
        return authority.withCurrentSource(scope, source.get(), current -> {
            write.run();
            return true;
        }).orElse(false);
    }

    public static Optional<Content> content(GeneralGraphVectorGate gate, GeneralGraphScope requested,
                                             TextSegment segment) {
        if (segment == null) return Optional.empty();
        Map<String, Object> meta = segment.metadata().toMap();
        if (!requiresGate(meta) || publicManual(meta)) return Optional.of(Content.from(segment));
        if (gate == null || requested == null || !requested.memoryEnabled()) return Optional.empty();
        GeneralGraphScope stored = claim(meta);
        if (stored == null || !stored.indexNamespace().equals(requested.indexNamespace())) return Optional.empty();
        return gate.current(requested, meta).map(gate.authority::evidenceContent);
    }

    private Optional<MemoryEvidence> current(GeneralGraphScope scope, Map<String, Object> meta) {
        long revision = positive(meta.get(PREFIX + "source_revision"));
        if (revision <= 0) return Optional.empty();
        return authority.source(scope, new KgChunk.SourceRef(value(meta, PREFIX + "source_id"), revision));
    }

    private static GeneralGraphScope claim(Map<String, Object> meta) {
        if (meta == null || !"true".equals(value(meta, PREFIX + "private"))) return null;
        try {
            return GeneralGraphScope.indexClaim(value(meta, PREFIX + "owner_namespace"),
                    positive(meta.get(PREFIX + "session_id")), positive(meta.get(PREFIX + "consent_epoch")));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
    private static String value(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        return value == null ? "" : String.valueOf(value);
    }
    private static long positive(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.matches("[1-9][0-9]{0,18}")) return -1;
        try { return Long.parseLong(text); } catch (NumberFormatException invalid) { return -1; }
    }
}
