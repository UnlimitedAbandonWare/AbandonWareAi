package com.abandonware.ai.service.guard;

import java.util.*;
public final class CitationGate {
  private final int minCitations;
  public CitationGate(int min){ this.minCitations=min; }
  public void check(List<Citation> cites){
    long trusted=0; if(cites!=null){ for(Citation c: cites){ if(c!=null && c.isTrusted()) trusted++; } }
    if(trusted<minCitations) throw new GateRejected("Not enough trusted citations (need >= "+minCitations+", got "+trusted+")");
  }
  public static interface Citation { boolean isTrusted(); }
}
