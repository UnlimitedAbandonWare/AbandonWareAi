package ai.abandonware.nova.orch.trace;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Idempotency guard: same idempotency key producing different digest => violation. */
public final class OrchIdempotencyGuard {

    private OrchIdempotencyGuard() {
    }

    public static boolean check(
            @Nullable DebugEventStore debugEventStore,
            String scope,
            String idempotencyKey,
            String digest,
            @Nullable Map<String, Object> meta) {

        String idHash = DigestUtils.sha1Hex(String.valueOf(idempotencyKey));
        String safeScope = safe(scope);
        String k = "orch.idempotency." + safeScope + "." + idHash;
        Object prev = TraceStore.putIfAbsent(k, digest);
        if (prev == null) {
            return true;
        }
        String prevS = String.valueOf(prev);
        if (prevS.equals(digest)) {
            return true;
        }

        TraceStore.inc("orch.idempotency.violation.count");
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("scope", safeScope);
        v.put("idKeyHash", idHash);
        v.put("prevDigest", prevS);
        v.put("digest", digest);
        if (meta != null && !meta.isEmpty()) {
            v.put("meta", SafeRedactor.diagnosticValue("orch.idempotency.meta", meta, 256));
        }
        TraceStore.append("orch.idempotency.violations", v);

        OrchEventEmitter.breadcrumbAndDebug(
                debugEventStore,
                DebugProbeType.GENERIC,
                DebugEventLevel.WARN,
                "orch.idempotency.violation",
                "[nova][orch] Idempotency violation (same key, different digest)",
                "OrchIdempotencyGuard.check",
                "orch.idempotency",
                "violation",
                safeScope,
                v,
                null);
        return false;
    }

    private static String safe(String s) {
        String label = SafeRedactor.traceLabelOrFallback(s, "unknown");
        return label.replace("hash:", "hash_").replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
