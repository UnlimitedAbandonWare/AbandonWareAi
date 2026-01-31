package ai.abandonware.nova.orch.trace;

import org.apache.commons.codec.digest.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

public final class OrchDigest {

    private OrchDigest() {
    }

    /** Order-sensitive canonical SHA-1. */
    public static String sha1Canonical(Object v) {
        StringBuilder sb = new StringBuilder(512);
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        appendCanonical(sb, v, 0, false, seen);
        return DigestUtils.sha1Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Treats top-level collections as an unordered multiset (for order-dependence detection).
     */
    public static String sha1Unordered(Object v) {
        if (v instanceof Collection<?> c) {
            ArrayList<String> parts = new ArrayList<>(c.size());
            for (Object o : c) {
                parts.add(sha1Canonical(o));
            }
            Collections.sort(parts);
            return DigestUtils.sha1Hex(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
        }
        return sha1Canonical(v);
    }

    private static void appendCanonical(
            StringBuilder sb,
            Object v,
            int depth,
            boolean unorderedCollections,
            IdentityHashMap<Object, Boolean> seen) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (seen.put(v, Boolean.TRUE) != null) {
            sb.append("<cycle>");
            return;
        }
        try {
            if (depth > 24) {
                sb.append("<max-depth>").append(String.valueOf(v));
                return;
            }
            if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean) {
                sb.append(String.valueOf(v));
                return;
            }
            if (v instanceof Map<?, ?> m) {
                sb.append('{');
                ArrayList<Map.Entry<?, ?>> entries = new ArrayList<>(m.entrySet());
                entries.sort(Comparator.comparing(e -> String.valueOf(e.getKey())));
                boolean first = true;
                for (Map.Entry<?, ?> e : entries) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(String.valueOf(e.getKey())).append(':');
                    appendCanonical(sb, e.getValue(), depth + 1, unorderedCollections, seen);
                }
                sb.append('}');
                return;
            }
            if (v instanceof Collection<?> c) {
                if (unorderedCollections) {
                    ArrayList<String> parts = new ArrayList<>(c.size());
                    for (Object o : c) {
                        parts.add(sha1Canonical(o));
                    }
                    Collections.sort(parts);
                    sb.append('[').append(String.join("|", parts)).append(']');
                    return;
                }
                sb.append('[');
                boolean first = true;
                for (Object o : c) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    appendCanonical(sb, o, depth + 1, unorderedCollections, seen);
                }
                sb.append(']');
                return;
            }
            sb.append(String.valueOf(v));
        } finally {
            seen.remove(v);
        }
    }
}
