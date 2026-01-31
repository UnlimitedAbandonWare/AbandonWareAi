package com.example.lms.service.rag.rerank;

import java.util.*;
import static java.lang.Math.max;
/** Greedy MMR-based diversity reranker. */
public final class DppDiversityReranker {
  public static final class Item {
    public final String id; public final float relevance; public final float[] emb;
    public Item(String id, float rel, float[] emb){ this.id=id; this.relevance=rel; this.emb=emb; }
  }
  private double sim(float[] a, float[] b){
    double dot=0,na=0,nb=0; int n=Math.min(a.length,b.length);
    for(int i=0;i<n;i++){ dot+=a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
    double denom=Math.sqrt(na)*Math.sqrt(nb)+1e-8; return denom==0?0:dot/denom;
  }
  public List<Item> rerank(List<Item> items, int k, double lambda){
    if(items==null||items.isEmpty()) return Collections.emptyList();
    List<Item> selected=new ArrayList<>(); Set<String> used=new HashSet<>(); int target=Math.min(k, items.size());
    while(selected.size()<target){
      Item best=null; double bestScore=-1e9;
      for(Item it: items){
        if(it==null||it.id==null||used.contains(it.id)) continue;
        double maxSim=0.0; for(Item s: selected) maxSim = Math.max(maxSim, sim(it.emb, s.emb));
        double score=lambda*it.relevance-(1.0-lambda)*maxSim;
        if(score>bestScore){ bestScore=score; best=it; }
      }
      if(best==null) break; selected.add(best); used.add(best.id);
    }
    return selected;
  }
}
