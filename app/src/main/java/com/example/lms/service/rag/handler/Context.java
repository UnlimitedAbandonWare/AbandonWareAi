package com.example.lms.service.rag.handler;

import java.util.HashMap;
import java.util.Map;

public class Context {
  private final Map<String,Object> map = new HashMap<>();
  public Map<String,Object> features(){ return map; }
  public void put(String k, Object v){ map.put(k, v); }
  public Object get(String k){ return map.get(k); }
}