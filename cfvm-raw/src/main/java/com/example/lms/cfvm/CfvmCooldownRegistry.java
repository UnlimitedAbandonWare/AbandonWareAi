package com.example.lms.cfvm;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CfvmCooldownRegistry {
  private final Map<String, Instant> last = new ConcurrentHashMap<>();
  private final int cooldownSeconds;
  public CfvmCooldownRegistry(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
  public boolean hit(String key) {
    Instant now = Instant.now();
    Instant prev = last.get(key);
    if (prev == null || prev.plusSeconds(cooldownSeconds).isBefore(now)) {
      last.put(key, now);
      return false; // not cooling down
    }
    return true;
  }
}