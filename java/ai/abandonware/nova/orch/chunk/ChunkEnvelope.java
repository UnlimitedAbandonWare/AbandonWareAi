package ai.abandonware.nova.orch.chunk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a user-provided chunk envelope and returns the payload + chunk
 * metadata.
 *
 * <p>
 * Supported formats:
 * <ul>
 * <li><code>⎔CHUNK⎔{json}\n&lt;payload&gt;</code></li>
 * <li><code>[CHUNK i/n] &lt;payload&gt;</code></li>
 * <li><code>[청크 i/n] &lt;payload&gt;</code></li>
 * </ul>
 *
 * <p>
 * This is a best-effort parser. If the input does not match a known envelope,
 * {@link #meta()} will be {@code null} and {@link #payload()} will equal the
 * raw input.
 * </p>
 */
public final class ChunkEnvelope {

    public static final String PREFIX = "⎔CHUNK⎔";

    private static final Pattern BRACKET_PATTERN = Pattern.compile(
            "^\\[(?:CHUNK|chunk|청크)\\s+(\\d+)\\s*/\\s*(\\d+)\\]\\s*(?:\\r?\\n)?(.*)$",
            Pattern.DOTALL);

    private final Meta meta; // null if not an explicit chunk envelope
    private final String payload;
    private final boolean explicit;

    private ChunkEnvelope(Meta meta, String payload, boolean explicit) {
        this.meta = meta;
        this.payload = payload;
        this.explicit = explicit;
    }

    public Meta meta() {
        return meta;
    }

    public String payload() {
        return payload;
    }

    public boolean explicit() {
        return explicit;
    }

    public static ChunkEnvelope parse(String raw) {
        if (raw == null) {
            return new ChunkEnvelope(null, null, false);
        }

        String trimmedLead = stripLeading(raw);

        // 1) ⎔CHUNK⎔{json}\n<payload>
        if (trimmedLead.startsWith(PREFIX)) {
            int nl = trimmedLead.indexOf('\n');
            if (nl > 0) {
                String head = trimmedLead.substring(0, nl).trim();
                String payload = trimmedLead.substring(nl + 1);
                String metaJson = head.substring(PREFIX.length()).trim();
                Meta meta = Meta.fromJsonBestEffort(metaJson);
                return new ChunkEnvelope(meta, payload, true);
            }
            // No newline -> treat whole thing as payload
            return new ChunkEnvelope(null, raw, false);
        }

        // 2) [CHUNK i/n] payload
        Matcher m = BRACKET_PATTERN.matcher(trimmedLead);
        if (m.matches()) {
            Integer idx = safeInt(m.group(1));
            Integer total = safeInt(m.group(2));
            String payload = m.group(3);
            Meta meta = new Meta();
            meta.idx = idx;
            meta.total = total;
            return new ChunkEnvelope(meta, payload, true);
        }

        // No envelope detected
        return new ChunkEnvelope(null, raw, false);
    }

    private static Integer safeInt(String s) {
        try {
            if (s == null)
                return null;
            return Integer.parseInt(s.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String stripLeading(String s) {
        if (s == null)
            return null;
        String out = s;
        if (!out.isEmpty() && out.charAt(0) == '\uFEFF') {
            out = out.substring(1);
        }
        return out.stripLeading();
    }

    /** Minimal metadata extracted from a chunk header. */
    public static final class Meta {
        private Integer idx;
        private Integer total;
        private String doc;
        private String chunkId;

        public Integer idx() {
            return idx;
        }

        public Integer total() {
            return total;
        }

        public String doc() {
            return doc;
        }

        public String chunkId() {
            return chunkId;
        }

        /**
         * Best-effort JSON extraction without depending on a JSON parser.
         *
         * <p>
         * Supports keys: idx, total, doc, chunkId.
         * </p>
         */
        public static Meta fromJsonBestEffort(String json) {
            if (json == null || json.isBlank()) {
                return null;
            }
            Meta m = new Meta();
            // Integers
            m.idx = findInt(json, "idx");
            m.total = findInt(json, "total");
            // Strings
            m.doc = findString(json, "doc");
            m.chunkId = findString(json, "chunkId");

            // Return null when we couldn't extract anything useful
            if (m.idx == null && m.total == null &&
                    (m.doc == null || m.doc.isBlank()) &&
                    (m.chunkId == null || m.chunkId.isBlank())) {
                return null;
            }
            return m;
        }

        private static Integer findInt(String json, String key) {
            try {
                Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
                Matcher m = p.matcher(json);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            } catch (Exception ignore) {
            }
            return null;
        }

        private static String findString(String json, String key) {
            try {
                Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"(.*?)\"");
                Matcher m = p.matcher(json);
                if (m.find()) {
                    return m.group(1);
                }
            } catch (Exception ignore) {
            }
            return null;
        }
    }
}
