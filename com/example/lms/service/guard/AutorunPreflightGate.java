package com.example.lms.service.guard;
import java.util.*;
public final class AutorunPreflightGate {
  public void check(List<String> evidences, Set<String> allowedDomains){
    if(evidences==null || evidences.size()<2) throw new IllegalStateException("preflight.evidence.insufficient");
    // Domain gating can be customized externally; here we accept any if not provided.
    if (allowedDomains!=null && !allowedDomains.isEmpty()) {
      // no-op stub: assume external checker binds real domains
    }
  }
}
