package ai.abandonware.nova.orch.trace;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deterministic ordering for TraceStore list payloads (List<Map<..>>). */
public final class TraceEventCanonicalizer {

    private TraceEventCanonicalizer() {
    }

    public static List<Map<String, Object>> canonicalize(@Nullable Object v, List<String> keyOrder) {
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add(unsafeCastMap(m));
            }
        }
        out.sort((a, b) -> compareMaps(a, b, keyOrder));
        return out;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Map<String, Object> unsafeCastMap(Map<?, ?> m) {
        return (Map) m;
    }

    private static int compareMaps(Map<String, Object> a, Map<String, Object> b, List<String> keyOrder) {
        long sa = asLong(a.get("seq"), Long.MIN_VALUE);
        long sb = asLong(b.get("seq"), Long.MIN_VALUE);
        if (sa != Long.MIN_VALUE || sb != Long.MIN_VALUE) {
            return Long.compare(sa, sb);
        }
        long ta = asLong(a.get("tsMs"), Long.MIN_VALUE);
        long tb = asLong(b.get("tsMs"), Long.MIN_VALUE);
        if (ta != Long.MIN_VALUE || tb != Long.MIN_VALUE) {
            return Long.compare(ta, tb);
        }
        for (String k : keyOrder) {
            int c = compareNullable(a.get(k), b.get(k));
            if (c != 0) {
                return c;
            }
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static int compareNullable(@Nullable Object a, @Nullable Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number an && b instanceof Number bn) {
            return Double.compare(an.doubleValue(), bn.doubleValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static long asLong(@Nullable Object v, long def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception ignore) {
            return def;
        }
    }
}
