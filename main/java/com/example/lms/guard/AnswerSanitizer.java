// src/main/java/com/example/lms/guard/AnswerSanitizer.java
package com.example.lms.guard;

import guard.FinalQualityGate;

import com.example.lms.prompt.PromptContext;



public interface AnswerSanitizer {

    default void setQualityGate(FinalQualityGate q) { /* no-op */ }

    default boolean approveEvidence(java.util.List<guard.FinalQualityGate.Evidence> evidences) { return true; }

    String sanitize(String answer, PromptContext ctx);
}

// Nova badge hook
// finalAnswer = com.example.lms.nova.NovaAnswerBadges.prependIfRuleBreak(finalAnswer);