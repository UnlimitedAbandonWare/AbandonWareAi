package com.example.lms.service.understanding;

import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.guard.GuardProfile;
import com.example.lms.jobs.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import java.util.List;

/** Versioned, immutable approval snapshot. Raw Q/A and request ThreadLocals are not persisted here. */
public record DeferredUnderstandingTask(
        int schemaVersion, String originalRunId, String ownerNamespace, long sessionId,
        String channel, long consentEpoch, long userMessageId, long userRevision,
        long assistantMessageId, long assistantRevision, String kind,
        String approvedQuestionHash, String approvedAnswerHash, GuardProfile guardProfile,
        MemoryMode memoryMode, boolean memorySaveAllowed, boolean requestUnderstanding, boolean sensitiveMemoryApproved,
        String modelId, long budgetMillis) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public DeferredUnderstandingTask {
        if (schemaVersion != 1 || originalRunId == null || !originalRunId.matches("[A-Za-z0-9_-]{1,64}")
                || !digest(ownerNamespace) || sessionId <= 0 || !"GENERAL".equals(channel) || consentEpoch <= 0
                || userMessageId <= 0 || assistantMessageId <= 0 || userMessageId == assistantMessageId
                || userRevision <= 0 || assistantRevision <= 0 || !"UNDERSTANDING".equals(kind)
                || !digest(approvedQuestionHash) || !digest(approvedAnswerHash)
                || guardProfile == null || guardProfile == GuardProfile.PROFILE_FREE || guardProfile == GuardProfile.WILD
                || memoryMode != MemoryMode.FULL || !memorySaveAllowed || !requestUnderstanding || !sensitiveMemoryApproved
                || modelId == null || modelId.isBlank() || modelId.length() > 200 || budgetMillis <= 0)
            throw new IllegalArgumentException("invalid_understanding_task");
    }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    /** Full tuple equality is checked again when a matching hash is found. */
    public List<Object> sourceTuple() {
        return List.of(ownerNamespace, sessionId, channel, consentEpoch, userMessageId, userRevision,
                assistantMessageId, assistantRevision, kind);
    }
    public String effectKey() { return hashJson(List.of("understanding-effect-v1", sourceTuple())); }
    public String admissionKey() { return hashJson(List.of("understanding-admission-v1", originalRunId, sourceTuple())); }
    public String requestFingerprint() { return hashJson(this); }
    public JobService.DerivedIdentity identity() {
        return new JobService.DerivedIdentity(ownerNamespace, sessionId, originalRunId, effectKey());
    }
    private static String hashJson(Object value) {
        try { return DigestUtils.sha256Hex(JSON.writeValueAsBytes(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("understanding_identity_encoding", invalid);
        }
    }
    public boolean approves(String question, String answer) {
        return question != null && answer != null && approvedQuestionHash.equals(DigestUtils.sha256Hex(question))
                && approvedAnswerHash.equals(DigestUtils.sha256Hex(answer));
    }
    @Override public String toString() { return "DeferredUnderstandingTask[v1,redacted]"; }
}
