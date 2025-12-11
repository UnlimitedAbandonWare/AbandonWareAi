package com.example.lms.cfvm;

import java.util.Map;

public interface NovaErrorBreak {
  enum Level { OK, WARN, BREAK }
  record Decision(Level level, String reason, Map<String,Object> hints) {}
  Decision evaluate(Map<String,Object> context);
}