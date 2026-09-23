package com.example.lms.service.rag.plan;

import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.guard.GuardProfile;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPolicyMapperTest {

    @Test
    void unknownGuardProfileFallsBack() {
        PlanPolicyMapper mapper = new PlanPolicyMapper();

        assertEquals(GuardProfile.NORMAL,
                mapper.resolveGuardProfile("not-a-profile", GuardProfile.NORMAL));
    }

    @Test
    void guardProfileParserOnlyCatchesIllegalArgumentException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/plan/PlanPolicyMapper.java"));
        String parserCall = "yield GuardProfile.valueOf(key);";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "guard profile parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 180));
        assertFalse(window.contains("catch (Exception"),
                "guard profile parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "guard profile parser must not swallow Throwable");
        assertTrue(window.contains("catch (IllegalArgumentException"),
                "guard profile parser should only catch IllegalArgumentException");
    }

    @Test
    void unknownGuardProfileCatchLeavesStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/plan/PlanPolicyMapper.java"));

        assertTrue(source.contains("TraceStore.put(\"rag.planPolicy.suppressed.guardProfile\", true)"));
        assertTrue(source.contains("TraceStore.put(\"rag.planPolicy.suppressed.stage\", \"guardProfile\")"));
        assertTrue(source.contains("TraceStore.put(\"rag.planPolicy.suppressed.stage\", \"memoryProfile\")"));
        assertTrue(source.contains("TraceStore.put(\"rag.planPolicy.suppressed.errorType\""));
        assertTrue(source.contains("rag.planPolicy.suppressed.guardProfile.reason"));
        assertFalse(source.contains("planGuardProfile={}"),
                "unknown plan profile values must not be logged raw");
    }

    @Test
    void unknownGuardProfileFallbackLeavesStableErrorTypeWithoutRawValue() {
        TraceStore.clear();
        PlanPolicyMapper mapper = new PlanPolicyMapper();

        assertEquals(GuardProfile.NORMAL,
                mapper.resolveGuardProfile("ownerToken=secret-profile", GuardProfile.NORMAL));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.planPolicy.suppressed.guardProfile"));
        assertEquals("unknown-profile", TraceStore.get("rag.planPolicy.suppressed.guardProfile.reason"));
        assertEquals("IllegalArgumentException", TraceStore.get("rag.planPolicy.suppressed.guardProfile.errorType"));
        assertEquals("guardProfile", TraceStore.get("rag.planPolicy.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("rag.planPolicy.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=secret-profile"));
    }

    @Test
    void unknownMemoryProfileFallbackLeavesStableErrorTypeWithoutRawValue() {
        TraceStore.clear();
        PlanPolicyMapper mapper = new PlanPolicyMapper();

        assertEquals(MemoryMode.FULL,
                mapper.resolveMemoryMode("ownerToken=secret-memory-profile", MemoryMode.FULL));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.planPolicy.suppressed.memoryProfile"));
        assertEquals("unknown-profile", TraceStore.get("rag.planPolicy.suppressed.memoryProfile.reason"));
        assertEquals("unknown_profile", TraceStore.get("rag.planPolicy.suppressed.memoryProfile.errorType"));
        assertEquals("memoryProfile", TraceStore.get("rag.planPolicy.suppressed.stage"));
        assertEquals("unknown_profile", TraceStore.get("rag.planPolicy.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=secret-memory-profile"));
    }
}
