package com.example.lms.learning.chat;

public record ResolvedLearningActor(
        LearningActorRole role,
        Long subjectId,
        String principalKey) {

    public ResolvedLearningActor {
        role = role == null ? LearningActorRole.ANONYMOUS : role;
        principalKey = principalKey == null ? "" : principalKey;
    }

    public boolean hasSubjectId() {
        return subjectId != null && subjectId > 0;
    }
}
