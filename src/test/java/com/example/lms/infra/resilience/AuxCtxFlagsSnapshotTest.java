package com.example.lms.infra.resilience;

import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuxCtxFlagsSnapshotTest {

    @Test
    void toMapRedactsBypassReason() {
        String secretShapedReason = "breaker open api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        AuxCtxFlagsSnapshot snapshot = new AuxCtxFlagsSnapshot(
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                0.25d,
                secretShapedReason);

        Map<String, Object> out = snapshot.toMap();

        assertEquals(SafeRedactor.traceLabelOrFallback(secretShapedReason, "unknown"), out.get("bypassReason"));
        assertFalse(String.valueOf(out.get("bypassReason")).contains("sk-"), out.toString());
    }
}
