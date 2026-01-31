package com.abandonware.ai.service.embedding;

import java.util.concurrent.*;

public class DecoratingEmbeddingModel {
  private final ConcurrentMap<String,float[]> cache = new ConcurrentHashMap<>();
  private final Object base; // placeholder for real embedder
  private final int ttlMinutes;
  public DecoratingEmbeddingModel(Object base, int ttlMinutes) {
    this.base = base; this.ttlMinutes = ttlMinutes;
  }
  public float[] embed(String input) {
    return cache.computeIfAbsent(input, k -> new float[0]);
  }
}