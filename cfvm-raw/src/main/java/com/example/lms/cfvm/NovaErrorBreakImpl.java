package com.example.lms.cfvm;

import java.util.Map;

public class NovaErrorBreakImpl implements NovaErrorBreak {
  private final CfvmRawService cfvm;
  private final NovaErrorBreakProperties props;

  public NovaErrorBreakImpl(CfvmRawService cfvm, NovaErrorBreakProperties props) {
    this.cfvm = cfvm;
    this.props = props;
  }

  @Override
  public Decision evaluate(Map<String,Object> ctx) {
    double s = cfvm.scoreOf(ctx).value();
    double brk = props.getBreakThreshold();
    double wrn = props.getWarnThreshold();
    if (s >= brk) return new Decision(Level.BREAK, "score>="+brk, Map.of());
    if (s >= wrn) return new Decision(Level.WARN,  "score>="+wrn, Map.of());
    return new Decision(Level.OK, "score<warn", Map.of());
  }
}