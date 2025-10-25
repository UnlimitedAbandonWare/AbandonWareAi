package com.example.lms.service.extreme; 
import java.util.*; 
public final class ExtremeZSystemHandler {
  public List<String> burst(String q,int n){ 
    List<String> o=new ArrayList<>(); 
    int N = Math.max(1, n);
    for(int i=0;i<N;i++) o.add(q+" :: aspect-"+(i+1)); 
    return o; 
  }
}
