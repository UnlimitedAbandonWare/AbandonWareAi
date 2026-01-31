package com.example.lms.config;

import java.io.IOException;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class PlanRepository {
  private final Map<String,Resource> plans = new LinkedHashMap<>();
  public PlanRepository(ResourceLoader rl) throws IOException {
    var resolver = new PathMatchingResourcePatternResolver(rl);
    Resource[] rs = resolver.getResources("classpath*:/plans/{default,brave,rulebreak,zero_break,hypernova}*.yaml");
    for (Resource r: rs) {
      plans.putIfAbsent(r.getFilename(), r);
    }
  }
  public Optional<Resource> get(String filename){ return Optional.ofNullable(plans.get(filename)); }
}