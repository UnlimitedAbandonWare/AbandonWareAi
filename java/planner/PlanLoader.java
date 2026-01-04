package planner;


import com.fasterxml.jackson.databind.ObjectMapper;
import planner.model.PlanSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PlanLoader {
  private final ObjectMapper mapper = createYamlOrJsonMapper();
  public PlanSpec load(String name) {
    try {
      Path p = Paths.get("plans", name + ".yaml");
      if (!Files.exists(p)) {
        // try classpath (for Spring Boot)
        p = Paths.get("src/main/resources/plans", name + ".yaml");
      }
      return mapper.readValue(Files.readAllBytes(p), PlanSpec.class);
    } catch (Exception e) { throw new RuntimeException(e); }
  }


  /**
   * Create a Jackson ObjectMapper that can read YAML if the YAML module is present,
   * otherwise fall back to a plain JSON ObjectMapper. Reflection is used to avoid
   * a hard dependency on jackson-dataformat-yaml at compile time.
   */
  private static com.fasterxml.jackson.databind.ObjectMapper createYamlOrJsonMapper() {
    try {
      Class<?> clazz = Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLMapper");
      return (com.fasterxml.jackson.databind.ObjectMapper) clazz.getDeclaredConstructor().newInstance();
    } catch (Throwable ignore) {
      return new com.fasterxml.jackson.databind.ObjectMapper();
    }
  }

}