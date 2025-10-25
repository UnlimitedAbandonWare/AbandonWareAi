package com.example.lms.service.overdrive; 
import java.util.*;
public final class AngerOverdriveNarrower {
  public <T> List<T> narrow(List<T> docs,int stage){ 
    int keep = switch(stage){ case 1->24; case 2->12; case 3->6; default->48; }; 
    return docs.subList(0, Math.min(docs.size(), keep)); 
  }
}
