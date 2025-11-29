package com.example.lms.service.embedding;

import java.time.*; import java.util.*;
/** Lightweight in-memory embedding cache (TTL). */
public final class EmbeddingCache {
  private static final class Entry{ final float[] vec; final long at; Entry(float[] v){ this.vec=v; this.at=System.currentTimeMillis(); } }
  private final Map<String,Entry> map = Collections.synchronizedMap(new LinkedHashMap<String,Entry>(1024,0.75f,true){
    protected boolean removeEldestEntry(Map.Entry<String,Entry> e){ return size()>200_000; }
  });
  private final long ttlMs;
  public EmbeddingCache(Duration ttl){ this.ttlMs = ttl.toMillis(); }
  public float[] getOrCompute(String key, java.util.function.Function<String,float[]> fn){
    String norm = key==null? "" : key.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
    Entry e = map.get(norm); long now=System.currentTimeMillis();
    if(e!=null && now-e.at<ttlMs) return e.vec;
    float[] v = fn.apply(norm); map.put(norm, new Entry(v)); return v;
  }
}
