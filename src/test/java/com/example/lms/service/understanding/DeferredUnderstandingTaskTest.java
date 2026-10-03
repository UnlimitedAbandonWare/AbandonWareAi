package com.example.lms.service.understanding;

import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.guard.GuardProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeferredUnderstandingTaskTest {
    private DeferredUnderstandingTask task(String run, long userRevision, long assistantRevision, long budget) {
        return new DeferredUnderstandingTask(1, run, "a".repeat(64), 1, "GENERAL", 1, 2, userRevision,
                3, assistantRevision, "UNDERSTANDING", DigestUtils.sha256Hex("question"),
                DigestUtils.sha256Hex("approved answer"), GuardProfile.NORMAL, MemoryMode.FULL,
                true, true, true, "synthetic-config", budget);
    }
    @Test void effectIdentitySurvivesNewRunAndRetryButAdmissionDoesNot() {
        var first = task("run-first", 100, 200, 500);
        var replay = task("run-second", 100, 200, 500);
        assertEquals(first.effectKey(), replay.effectKey());
        assertNotEquals(first.admissionKey(), replay.admissionKey());
        assertNotEquals(first.requestFingerprint(), replay.requestFingerprint());
        assertEquals(first.sourceTuple(), replay.sourceTuple());
    }
    @Test void bothRevisionFingerprintsAndApprovedPolicyAffectTheRightIdentity() {
        var first = task("run", 100, 200, 500);
        assertNotEquals(first.effectKey(), task("run", 101, 200, 500).effectKey());
        assertNotEquals(first.effectKey(), task("run", 100, 201, 500).effectKey());
        assertEquals(first.admissionKey(), task("run", 100, 200, 400).admissionKey());
        assertNotEquals(first.requestFingerprint(), task("run", 100, 200, 400).requestFingerprint());
        assertTrue(first.approves("question", "approved answer"));
        assertFalse(first.approves("edited question", "approved answer"));
        assertFalse(first.approves("question", "transcript replaced"));
    }
    @Test void serializationIsStableAndContainsNoRawQuestionOrAnswer() throws Exception {
        var first = task("run", 100, 200, 500);
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(first);
        assertFalse(json.contains("approved answer"));
        assertEquals(first, mapper.readValue(json, DeferredUnderstandingTask.class));
        assertEquals(first.requestFingerprint(), mapper.readValue(json, DeferredUnderstandingTask.class).requestFingerprint());
        assertEquals("DeferredUnderstandingTask[v1,redacted]", first.toString());
    }
    @Test void malformedVersionAndUnapprovedPolicyFailClosed() throws Exception {
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(task("run", 100, 200, 500));
        assertThrows(Exception.class, () -> mapper.readValue(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), DeferredUnderstandingTask.class));
        assertThrows(Exception.class, () -> mapper.readValue(json.replace("\"FULL\"", "\"HYBRID\""), DeferredUnderstandingTask.class));
        assertThrows(Exception.class, () -> mapper.readValue(json.replace("\"memorySaveAllowed\":true", "\"memorySaveAllowed\":false"), DeferredUnderstandingTask.class));
    }
    @Test void missingSensitiveMemoryApprovalFailsClosed() throws Exception {
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(task("run", 100, 200, 500));
        String legacy = json.replaceAll("\\\"sensitiveMemoryApproved\\\":true,?", "");
        assertThrows(Exception.class, () -> mapper.readValue(legacy, DeferredUnderstandingTask.class));
    }
}
