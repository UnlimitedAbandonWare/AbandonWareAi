package strategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.util.Map;
public class PlanLoader {
  private static final Logger log = LoggerFactory.getLogger(PlanLoader.class);

  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
  private final String defaultPlan;
  public PlanLoader(String defaultPlan){ this.defaultPlan = defaultPlan; }
  @SuppressWarnings("unchecked")
  public Plan load(String name) {
    String plan = (name==null || name.isBlank()) ? defaultPlan : name;
    String path = "/plans/" + plan;
    try (InputStream in = getClass().getResourceAsStream(path)) {
      Map<String,Object> m = yaml.readValue(in, Map.class);
      Map<String,Integer> k = (Map<String,Integer>) m.getOrDefault("k", Map.of());
      Map<String,Boolean> flags = (Map<String,Boolean>) m.getOrDefault("flags", Map.of());
      return new Plan(k.getOrDefault("web",10), k.getOrDefault("vector",5),
              flags.getOrDefault("officialSourcesOnly", false),
              flags.getOrDefault("diversityReranker", true));
    } catch(Exception e){
      logFailSoft("load", e);
      return new Plan(10,5,false,true);
    }
  }
  private static void logFailSoft(String stage, Exception e) {
    if (log.isDebugEnabled()) {
      String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
      log.debug("[AWX][strategy][plan-loader] failSoft stage={} errorType={}", stage, errorType);
    }
  }
  public record Plan(int webK, int vectorK, boolean officialOnly, boolean diversity){}
}
