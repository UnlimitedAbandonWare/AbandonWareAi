package com.example.lms.service.retrieval.bm25;
import java.util.*; 
public class Bm25Retriever {
  public static class Doc { public final String id, text; public final double score; public Doc(String i,String t,double s){id=i;text=t;score=s;} }
  public List<Doc> search(String q,int k){ return Collections.emptyList(); } // safe stub
}
