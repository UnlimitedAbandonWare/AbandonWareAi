package com.example.lms.service.rag.handler;

import javax.annotation.Nullable;



public final class RuleBreakRetrievalDecorator {

  private RuleBreakRetrievalDecorator() {}

  public static <T> T safe(T obj) { return obj; }

  public static Object decorateOptions(Object opt, @Nullable com.example.lms.guard.rulebreak.RuleBreakContext rb) {
    if (rb == null || !rb.isValid() || opt == null) return opt;
    try {
      var mGet = opt.getClass().getMethod("getWebTopK");
      var mSet = opt.getClass().getMethod("setWebTopK", int.class);
      int v = (int) mGet.invoke(opt);
      if (rb.getPolicy().allowsTopKBoost()) {
         int newV = Math.min(v*2, 8);
         mSet.invoke(opt, newV);
      }
    } catch (Exception ignore) {}
    try {
      var g = opt.getClass().getMethod("getTimeoutMillis");
      var s = opt.getClass().getMethod("setTimeoutMillis", int.class);
      int v = (int) g.invoke(opt);
      s.invoke(opt, v + rb.getPolicy().getExtraTimeoutMs());
    } catch (Exception ignore) {}
    try {
      if (rb.getPolicy().allowsHedgeDisable()) {
        var hs = opt.getClass().getMethod("setHedge", boolean.class);
        hs.invoke(opt, false);
      }
    } catch (Exception ignore) {}
    return opt;
  }
}