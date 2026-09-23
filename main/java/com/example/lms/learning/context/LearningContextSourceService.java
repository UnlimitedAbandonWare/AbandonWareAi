package com.example.lms.learning.context;

import com.example.lms.learning.chat.LearningActorRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningContextSourceService {

    @Transactional(readOnly = true)
    public LearningContextSnapshot buildForCurrentActor() {
        return LearningContextSnapshot.empty(LearningActorRole.ANONYMOUS);
    }

    @Transactional(readOnly = true)
    public RagLearningSupportContext buildRagSupportForCurrentActor() {
        return RagLearningSupportContext.empty(LearningActorRole.ANONYMOUS);
    }
}
