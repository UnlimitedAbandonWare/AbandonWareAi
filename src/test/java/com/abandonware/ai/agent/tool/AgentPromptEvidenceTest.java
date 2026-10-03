package com.abandonware.ai.agent.tool;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.policy.ToolPolicyEnforcer;
import com.abandonware.ai.agent.tool.impl.WebSearchTool;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.integrations.WebSearchGateway;
import com.example.lms.prompt.*;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class AgentPromptEvidenceTest {
 @AfterEach void clear(){ TraceStore.clear(); TraceContext.cleanupCurrentThread(); }
 @Test void approvedSameRequestEvidenceReachesCanonicalPromptWithoutDiagnosticText() {
  var invoker=invoker((q,k,l)->List.of(
    Map.of("url","https://docs.example.test/guide","snippet","semantic-needle "+"x".repeat(900),"title","Guide"),
    Map.of("url","https://docs.example.test/guide","snippet","duplicate"),
    Map.of("url","https://user:pass@docs.example.test/unsafe","snippet","invalid")));
  List<Content> evidence=new ArrayList<>();
  try(var ctx=TraceContext.attach("s1","same-request").startWithBudget(Duration.ofSeconds(5))) {
   var result=invoker.invokeForPrompt("web.search",Map.of("query","synthetic"),new ToolContext("s1",null,Map.of("allowWeb",true)),true,evidence::addAll);
   assertThat(result.get("executionStatus")).isEqualTo("OK");
   assertThat(evidence).hasSize(1);
   assertThat(evidence.get(0).textSegment().text()).contains("semantic-needle").hasSizeLessThanOrEqualTo(800);
   var prompt=PromptContext.builder().ragEnabled(true).web(evidence).build();
   assertThat(new StandardPromptBuilder().build(prompt).toString()).contains("semantic-needle");
   assertThat(TraceStore.getAll().toString()).doesNotContain("semantic-needle","xxxxxx","user:pass");
   assertThat(result.toString()).doesNotContain("semantic-needle","xxxxxx");
  }
 }
 @Test void deniedOwnerAndDisallowedWebCannotReachGatewayOrSink() {
  var calls=new AtomicInteger(); var invoker=invoker((q,k,l)->{calls.incrementAndGet();return List.of();});
  List<Content> evidence=new ArrayList<>();
  assertThatThrownBy(()->invoker.invokeForPrompt("web.search",Map.of("query","q"),new ToolContext("s1",null),false,evidence::addAll)).isInstanceOf(ToolInvocationException.class);
  var result=invoker.invokeForPrompt("web.search",Map.of("query","q"),new ToolContext("s1",null,Map.of("allowWeb",false)),true,evidence::addAll);
  assertThat(result.get("executionStatus")).isEqualTo("SKIPPED");
  assertThat(calls).hasValue(0); assertThat(evidence).isEmpty();
 }
 @Test void cancelledResultCannotReachPromptEvenForReadOnlyFalseManifest() {
  var invoker=invoker((q,k,l)->{TraceContext.current().timeBudget().cancel();return List.of(Map.of("url","https://example.test/doc","snippet","late-needle"));});
  List<Content> evidence=new ArrayList<>();
  try(var ctx=TraceContext.attach("s1","late").startWithBudget(Duration.ofSeconds(5))) {
   assertThatThrownBy(()->invoker.invokeForPrompt("web.search",Map.of("query","q"),new ToolContext("s1",null),true,evidence::addAll)).isInstanceOf(ToolInvocationException.class);
   assertThat(evidence).isEmpty();
  }
 }
 @Test void failSoftZeroAndSkippedRemainDistinct() {
  for(String reason:List.of("all-providers-failed","zero-result","no-eligible-provider")){
   var invoker=invoker((q,k,l)->{TraceStore.put("agent.acmeGateway.result.reason",reason);return List.of();});
   var result=invoker.invokeForPrompt("web.search",Map.of("query","q"),new ToolContext("s1",null),true,e->{});
   assertThat(result.get("executionStatus")).isEqualTo(reason.equals("zero-result")?"OK":reason.equals("no-eligible-provider")?"SKIPPED":"FAIL_SOFT");
  }
 }
 private static AgentToolInvoker invoker(WebSearchGateway gateway){
  var registry=new ToolRegistry();registry.register(new WebSearchTool(gateway));
  var catalog=new ToolManifestCatalog(new MockEnvironment().withProperty("agent.tools.web-search.enabled","true"),()->registry);
  return new AgentToolInvoker(registry,catalog,new ToolPolicyEnforcer(),null,null,new AgentToolArtifactWriter());
 }
}
