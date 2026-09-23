package planner;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import planner.model.PlanSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PlanLoader {
  private final ObjectMapper mapper = new YAMLMapper();
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


}
