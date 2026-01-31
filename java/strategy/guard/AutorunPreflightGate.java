package strategy.guard;

import java.util.*;
@SuppressWarnings("unused")
public class AutorunPreflightGate {
  public static class Result {
    public final boolean pass; public final String reason;
    public Result(boolean p, String r) { this.pass=p; this.reason=r; }
  }
  public Result evaluate(List<?> contextDocs, int minCitations) {
    int n = contextDocs==null?0:contextDocs.size();
    if (n < minCitations) return new Result(false, "LOW_CONFIDENCE: insufficient evidence ("+n+"<"+minCitations+")");
    return new Result(true, "OK");
  }
}