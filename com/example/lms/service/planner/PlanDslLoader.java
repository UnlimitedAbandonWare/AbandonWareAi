package com.example.lms.service.planner;
import java.io.InputStream;
import java.util.Map;
import java.util.HashMap;
@SuppressWarnings("unchecked")
public final class PlanDslLoader {
  public Map<String,Object> loadPlan(String name){
    String path="/plans/"+name+".yaml";
    try (InputStream in = PlanDslLoader.class.getResourceAsStream(path)) {
      if (in == null) {
        Map<String,Object> bare = new HashMap<>();
        bare.put("name", name);
        return bare;
      }
      try {
        // Try to use SnakeYAML via reflection if present
        Class<?> yamlClz = Class.forName("org.yaml.snakeyaml.Yaml");
        Object yaml = yamlClz.getDeclaredConstructor().newInstance();
        Map<String,Object> m = (Map<String,Object>) yamlClz.getMethod("load", InputStream.class).invoke(yaml, in);
        if (m == null) {
          m = new HashMap<>();
          m.put("name", name);
        }
        return m;
      } catch (Throwable t) {
        Map<String,Object> bare = new HashMap<>();
        bare.put("name", name);
        return bare;
      }
    } catch (Exception e) {
      Map<String,Object> bare = new HashMap<>();
      bare.put("name", name);
      return bare;
    }
  }
}
