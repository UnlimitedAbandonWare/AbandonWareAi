package com.example.lms.service.guard;
import java.util.*; 
public final class CitationGate {
  private final int minSources;
  public CitationGate(int minSources){ this.minSources=minSources; }
  public void check(List<String> cites){
    if(cites==null || cites.stream().filter(Objects::nonNull).distinct().count()<minSources)
      throw new IllegalStateException("citation.min-sources.not.met");
  }
}
