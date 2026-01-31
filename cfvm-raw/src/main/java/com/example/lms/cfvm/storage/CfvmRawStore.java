package com.example.lms.cfvm.storage;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

public interface CfvmRawStore {
  void append(Map<String,Object> event);
  Stream<Map<String,Object>> scan(Duration window);
}