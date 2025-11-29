package config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

import planner.PlanLoader;
import planner.PlannerNexus;
import service.rag.policy.KAllocationPolicy;
import service.rag.calibration.IsotonicScoreCalibrator;
import service.guard.RerankerConcurrencyGuard;
import web.RuleBreakInterceptor;
import integrations.mcp.McpClient;
import integrations.mcp.tools.MCPPingTool;
import guard.PIISanitizer;

import jakarta.servlet.Filter;

@Configuration
public class OrchestrationConfig {
  @Bean PlanLoader planLoader(){ return new PlanLoader(); }

  @Bean PlannerNexus plannerNexus(PlanLoader l, @Value("${planner.plan:safe_autorun}") String name){
    try { return new PlannerNexus(l.load(name)); }
    catch (Exception e){ return new PlannerNexus(null); }
  }

  @Bean KAllocationPolicy kAllocationPolicy(PlannerNexus p){ return new KAllocationPolicy(p); }

  @Bean IsotonicScoreCalibrator webCal() {
    return new IsotonicScoreCalibrator(new double[]{0,0.1,0.3,0.6,1}, new double[]{0,0.12,0.35,0.7,1});
  }

  @Bean RerankerConcurrencyGuard rerankerGuard(@Value("${reranker.onnx.maxConcurrent:4}") int n){
    return new RerankerConcurrencyGuard(n);
  }

  @Bean Filter ruleBreak(@Value("${rulebreak.header:X-RuleBreak-Token}") String h,
                         @Value("${rulebreak.token:}") String t){
    return new RuleBreakInterceptor(h,t);
  }

  @Bean McpClient mcpClient(@Value("${mcp.serverUrl:http://localhost:5173}") String url){
    return new McpClient(url);
  }
  @Bean MCPPingTool mcpPingTool(McpClient c){ return new MCPPingTool(c); }

  @Bean PIISanitizer pii(){ return new PIISanitizer(); }
}