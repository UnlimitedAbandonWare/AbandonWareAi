package ai.abandonware.nova.orch.chunk;

/**
 * Parses a server-side rolling summary meta message.
 *
 * <p>Stored format:
 * <pre>
 *   ⎔RSUM⎔{json}\n<summary>
 * </pre>
 */
public record RollingSummaryEnvelope(String metaJson, String summary) {

    public static final String PREFIX = "⎔RSUM⎔";

    /**
     * Returns {@code null} when the input is not a rolling-summary meta message.
     */
    public static RollingSummaryEnvelope parse(String raw) {
        if (raw == null) {
            return null;
        }

        String s = stripLeading(raw);
        if (!s.startsWith(PREFIX)) {
            return null;
        }

        // Expect: PREFIX + metaJson + \n + summary
        int nl = s.indexOf('\n');
        if (nl <= 0) {
            return null;
        }

        String head = s.substring(0, nl).trim();
        String meta = head.substring(PREFIX.length()).trim();
        String body = s.substring(nl + 1);

        return new RollingSummaryEnvelope(meta, body);
    }

    private static String stripLeading(String s) {
        // Normalize BOM + leading whitespace
        if (s == null) {
            return null;
        }
        String out = s;
        if (!out.isEmpty() && out.charAt(0) == '\uFEFF') {
            out = out.substring(1);
        }
        return out.stripLeading();
    }
}
