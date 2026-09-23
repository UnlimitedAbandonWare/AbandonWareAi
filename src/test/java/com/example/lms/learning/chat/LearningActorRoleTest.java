package com.example.lms.learning.chat;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LearningActorRoleTest {

    @Test
    void mapsExistingLmsRolesToLearningRoles() {
        assertEquals(LearningActorRole.STUDENT,
                LearningActorRole.fromAuthorities(List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        assertEquals(LearningActorRole.TEACHER,
                LearningActorRole.fromAuthorities(List.of(new SimpleGrantedAuthority("ROLE_PROF"))));
        assertEquals(LearningActorRole.ADMIN,
                LearningActorRole.fromAuthorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    void exposesTrainingRagPresentationLabelsWithoutRenamingCompatibilityRoles() {
        assertEquals("TRAINING_USER", LearningActorRole.STUDENT.trainingRagLabel());
        assertEquals("TRAINING_SUPPORT", LearningActorRole.TEACHER.trainingRagLabel());
        assertEquals("RAG_ADMIN", LearningActorRole.ADMIN.trainingRagLabel());
        assertEquals("ANONYMOUS", LearningActorRole.ANONYMOUS.trainingRagLabel());
    }

    @Test
    void unknownAuthorityIsAnonymous() {
        assertEquals(LearningActorRole.ANONYMOUS,
                LearningActorRole.fromAuthorities(List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
    }
}
