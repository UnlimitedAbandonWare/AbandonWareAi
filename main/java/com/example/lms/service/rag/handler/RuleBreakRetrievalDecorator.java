package com.example.lms.service.rag.handler;

import javax.annotation.Nullable;



public final class RuleBreakRetrievalDecorator {

  private RuleBreakRetrievalDecorator() {}

  public static <T> T safe(T obj) { return obj; }

  public static Object decorateOptions(Object opt, @Nullable com.example.lms.guard.rulebreak.RuleBreakContext rb) {
    // No active typed retrieval-options contract exists on this runtime path.
    // Keep the API stable, but do not mutate unknown objects through reflection.
    return opt;
  }
}
