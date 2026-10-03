package ai.abandonware.nova.orch.aop;
import ai.abandonware.nova.config.*;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.example.lms.llm.gateway.*;
import com.example.lms.routing.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class LlmRouterTierPolicyTest {
 @Test void cheapestEligibleTierIsAppliedBeforeBandit() {
  var p=routes(); var f=filter(p, Set.of());
  assertThat(f.eligible("local",p.getModels().get("local"))).isTrue();
  assertThat(f.tier("cheap",p.getModels().get("cheap"))).isGreaterThan(f.tier("local",p.getModels().get("local")));
  assertThat(new LlmRouterBandit(p).pick("llmrouter.auto",f).key()).isEqualTo("local");
 }
 @Test void unhealthyLocalPromotesOnlyToCheapTier() {
  var p=routes(); var f=filter(p,Set.of("local"));
  assertThat(f.eligible("local",p.getModels().get("local"))).isFalse();
  assertThat(f.eligible("cheap",p.getModels().get("cheap"))).isTrue();
  assertThat(new LlmRouterBandit(p).pick("llmrouter.auto",f).key()).isEqualTo("cheap");
 }
 @Test void unlistedAndEmbeddingModelsNeverEnterChatCandidates() {
  var p=routes(); p.getModels().get("local").setName("qwen3-embedding:4b");
  p.getModels().get("cheap").setProvider("unlisted"); var f=filter(p,Set.of());
  assertThat(f.eligible("local",p.getModels().get("local"))).isFalse();
  assertThat(f.eligible("cheap",p.getModels().get("cheap"))).isFalse();
  assertThat(f.eligible("paid",p.getModels().get("paid"))).isTrue();
 }
 @Test void fallbackRetainsTheCheapestEligibleLocalTier() {
  var p=routes(); p.getModels().get("local").setBaseUrl("http://127.0.0.1:11434/v1");
  p.getModels().get("paid").setBaseUrl("https://api.openai.com/v1");
  p.getModels().get("cheap").setBaseUrl("https://api.groq.com/openai/v1");
  var probe=mock(HybridLlmGatewayProbeService.class);
  when(probe.cloudFallbackEnabled()).thenReturn(true);
  when(probe.evaluate(anyString(),any(),anyString())).thenAnswer(inv -> {
   String key=inv.getArgument(0); LlmRouterProperties.ModelConfig cfg=inv.getArgument(1);
   return RoutingEligibility.eligible(key,cfg.getProvider(),cfg.getName(),"chat",1,false,Map.of());
  });
  var aspect=new LlmRouterAspect(new MockEnvironment(),p,new LlmRouterBandit(p),new NovaModelGuardProperties(),null,probe,null,null);
  LlmRouterBandit.Selected fallback=ReflectionTestUtils.invokeMethod(aspect,"eligibleFallbackSelection",
    new LlmRouterBandit.Selected("paid",p.getModels().get("paid")),null);
  assertThat(fallback.key()).isEqualTo("local");
 }
 @Test void profileActivationAndUserReplayAreSeparated() {
  var env=new MockEnvironment(); env.setActiveProfiles("agent-spend-guard");
  assertThat(ApiSpendAttribution.agentModeActive(env)).isTrue();
  var policy=new ApiRoutingPolicySnapshot(env);
  assertThat(AgentApiSpendGuard.beforeCall(policy,"llm_factory_build","openai","gpt-4o","test","same",false).allow()).isFalse();
  AgentApiSpendGuard.afterSuccess("user_request","groq","fixture","test","user-repeat",1,1,"llm_cheap");
  assertThat(AgentApiSpendGuard.beforeCall(policy,"user_request","groq","fixture","test","user-repeat",false).allow()).isTrue();
 }
 private static LlmRouterProperties routes() {
  var p=new LlmRouterProperties(); p.setEnabled(true);
  var m=new LinkedHashMap<String,LlmRouterProperties.ModelConfig>();
  m.put("paid",config("openai","gpt-4o",100)); m.put("cheap",config("groq","fixture-cheap",10));
  m.put("local",config("local","gemma4:26b",1)); p.setModels(m); return p;
 }
 private static LlmRouterProperties.ModelConfig config(String provider,String name,double weight) {
  var c=new LlmRouterProperties.ModelConfig(); c.setEnabled(true); c.setProvider(provider);
  c.setName(name); c.setWeight(weight); c.setStage("chat"); return c;
 }
 private static LlmRouterBandit.RouteEligibilityFilter filter(LlmRouterProperties p,Set<String> denied) {
  var probe=mock(HybridLlmGatewayProbeService.class); when(probe.isEnforce()).thenReturn(true);
  when(probe.evaluate(anyString(),any(),anyString())).thenAnswer(inv -> {
   String k=inv.getArgument(0); LlmRouterProperties.ModelConfig c=inv.getArgument(1);
   return denied.contains(k) ? RoutingEligibility.blocked(k,c.getProvider(),c.getName(),"chat",0,false,List.of(LlmFailureClass.UNKNOWN),Map.of())
    : RoutingEligibility.eligible(k,c.getProvider(),c.getName(),"chat",1,false,Map.of());
  });
  var aspect=new LlmRouterAspect(new MockEnvironment(),p,new LlmRouterBandit(p),new NovaModelGuardProperties(),null,probe,null,null);
  return ReflectionTestUtils.invokeMethod(aspect,"gatewayFilter",(Object)null);
 }
}
