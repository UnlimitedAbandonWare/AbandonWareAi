package com.example.lms.api;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.policy.ToolPolicyEnforcer;
import com.abandonware.ai.agent.tool.*;
import com.abandonware.ai.agent.tool.impl.WebSearchTool;
import com.example.lms.prompt.*;
import com.example.lms.service.ChatWorkflow;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class ChatApiAgentPromptEvidenceTest {
 @AfterEach void clear(){TraceStore.clear();TraceContext.cleanupCurrentThread();}
 @Test void admittedSupplierPreservesMetadataAndExistingAuthority() {
  var calls=new AtomicInteger();var registry=new ToolRegistry();
  registry.register(new WebSearchTool((q,k,l)->{calls.incrementAndGet();return List.of(Map.of("url","https://docs.example.test/canonical","snippet","bounded controller evidence https://evil.example.test/decoy"));}));
  var catalog=new ToolManifestCatalog(new MockEnvironment().withProperty("agent.tools.web-search.enabled","true"),()->registry);
  var invoker=new AgentToolInvoker(registry,catalog,new ToolPolicyEnforcer(),null,null,new AgentToolArtifactWriter());
  var controller=mock(ChatApiController.class,CALLS_REAL_METHODS);
  ReflectionTestUtils.setField(controller,"agentToolInvoker",invoker);
  ReflectionTestUtils.setField(controller,"agentWebSearchEnabled",true);
  assertThat((Boolean)ReflectionTestUtils.invokeMethod(controller,"agentWebRequestAuthorized",false)).isFalse();
  assertThat((Boolean)ReflectionTestUtils.invokeMethod(controller,"agentWebRequestAuthorized",true)).isTrue();
  try(var trace=TraceContext.attach("session","request").startWithBudget(Duration.ofSeconds(4))){
   ChatWorkflow.WebEvidenceSupplier supplier=q->ReflectionTestUtils.invokeMethod(controller,"agentPromptSearch",q,3,"session",true,true);
   var evidence=supplier.evidence("synthetic");
   assertThat(evidence).hasSize(1);
   assertThat(evidence.get(0).textSegment().metadata().getString("source")).isEqualTo("https://docs.example.test/canonical");
   assertThat(new StandardPromptBuilder().build(PromptContext.builder().ragEnabled(true).web(evidence).build()).toString()).contains("bounded controller evidence");
   assertThat(calls).hasValue(1);
   assertThat(TraceStore.getAll().toString()).doesNotContain("bounded controller evidence","evil.example");
  }
  ReflectionTestUtils.setField(controller,"agentWebSearchEnabled",false);
  assertThat((Boolean)ReflectionTestUtils.invokeMethod(controller,"agentWebRequestAuthorized",true)).isFalse();
 }

 @ParameterizedTest
 @CsvSource({
  "exception,FAIL_SOFT", "all-providers-failed,FAIL_SOFT",
  "ranking-failure,FAIL_SOFT", "ranking-nonresponse,FAIL_SOFT",
  "zero-result,OK", "no-eligible-provider,SKIPPED"
 })
 void supplierRetainsSearchOutcomeWhenEvidenceIsEmpty(String reason, String expectedStatus) {
  var calls = new AtomicInteger();
  var controller = controller((q,k,l) -> {
   calls.incrementAndGet();
   if ("exception".equals(reason)) throw new IllegalStateException("synthetic private failure detail");
   TraceStore.put("agent.acmeGateway.result.reason", reason);
   return List.of();
  });
  try (var trace = TraceContext.attach("session", "b02-outcome").startWithBudget(Duration.ofSeconds(5))) {
   var evidence = search(controller, true);
   assertThat(evidence).isEmpty();
   assertThat(calls).hasValue(1);
   assertThat(TraceStore.get("tool.invoke.status")).isEqualTo(expectedStatus);
   assertThat(TraceStore.get("agent.webSearch.prompt.status")).isEqualTo(expectedStatus);
   assertThat(TraceStore.get("agent.webSearch.prompt.returnedCount")).isEqualTo(0);
   assertThat(TraceStore.getAll().toString()).doesNotContain("synthetic private failure detail");
   assertThat(new StandardPromptBuilder().build(PromptContext.builder().ragEnabled(true).web(evidence).build()).toString())
     .doesNotContain("synthetic private failure detail", "FAIL_SOFT", "all-providers-failed");
  }
 }

 @ParameterizedTest
 @CsvSource({"403,SKIPPED", "408,FAIL_SOFT", "429,FAIL_SOFT", "500,FAIL_SOFT"})
 void supplierDoesNotCallExecutionFailuresSkipped(int httpStatus, String expectedStatus) {
  var invoker = mock(AgentToolInvoker.class);
  when(invoker.invokeForPrompt(eq("web.search"), anyMap(), any(), eq(true), any()))
    .thenThrow(new ToolInvocationException(httpStatus, "synthetic_tool_failure"));
  var controller = mock(ChatApiController.class, CALLS_REAL_METHODS);
  ReflectionTestUtils.setField(controller, "agentToolInvoker", invoker);
  assertThat(search(controller, true)).isEmpty();
  assertThat(TraceStore.get("agent.webSearch.prompt.status")).isEqualTo(expectedStatus);
  assertThat(TraceStore.get("agent.webSearch.prompt.reasonCode")).isEqualTo("synthetic_tool_failure");
  assertThat(TraceStore.get("agent.webSearch.prompt.returnedCount")).isEqualTo(0);
  verify(invoker, times(1)).invokeForPrompt(eq("web.search"), anyMap(), any(), eq(true), any());
 }

 @Test void successfulEmptySearchClearsPreviousFailureAtConsumer() {
  var calls = new AtomicInteger();
  var controller = controller((q,k,l) -> {
   if (calls.incrementAndGet() == 1) throw new IllegalStateException("synthetic failure");
   return List.of();
  });
  try (var trace = TraceContext.attach("session", "b02-sequential").startWithBudget(Duration.ofSeconds(5))) {
   assertThat(search(controller, true)).isEmpty();
   assertThat(TraceStore.get("agent.webSearch.prompt.status")).isEqualTo("FAIL_SOFT");
   assertThat(TraceStore.get("agent.webSearch.prompt.reasonCode")).isEqualTo("web_search_failed");
   assertThat(search(controller, true)).isEmpty();
   assertThat(TraceStore.get("agent.webSearch.prompt.status")).isEqualTo("OK");
   assertThat(TraceStore.get("agent.webSearch.prompt.reasonCode")).isNull();
   assertThat(calls).hasValue(2);
  }
 }

 @Test void explicitOffRemainsSkippedWithoutCallingGateway() {
  var calls = new AtomicInteger();
  var controller = controller((q,k,l) -> { calls.incrementAndGet(); return List.of(); });
  TraceStore.put("agent.webSearch.prompt.status", "FAIL_SOFT");
  TraceStore.put("agent.webSearch.prompt.reasonCode", "prior_failure");
  assertThat(search(controller, false)).isEmpty();
  assertThat(calls).hasValue(0);
  assertThat(TraceStore.get("agent.webSearch.prompt.status")).isEqualTo("SKIPPED");
  assertThat(TraceStore.get("agent.webSearch.prompt.reasonCode")).isEqualTo("web_search_request_disallowed");
  assertThat(TraceStore.get("agent.webSearch.prompt.returnedCount")).isEqualTo(0);
 }

 private static ChatApiController controller(com.abandonware.ai.agent.integrations.WebSearchGateway gateway) {
  var registry = new ToolRegistry();
  registry.register(new WebSearchTool(gateway));
  var catalog = new ToolManifestCatalog(new MockEnvironment().withProperty("agent.tools.web-search.enabled", "true"), () -> registry);
  var invoker = new AgentToolInvoker(registry, catalog, new ToolPolicyEnforcer(), null, null, new AgentToolArtifactWriter());
  var controller = mock(ChatApiController.class, CALLS_REAL_METHODS);
  ReflectionTestUtils.setField(controller, "agentToolInvoker", invoker);
  return controller;
 }

 private static List<dev.langchain4j.rag.content.Content> search(ChatApiController controller, boolean allowWeb) {
  ChatWorkflow.WebEvidenceSupplier supplier = q -> ReflectionTestUtils.invokeMethod(
    controller, "agentPromptSearch", q, 3, "session", allowWeb, true);
  return supplier.evidence("synthetic");
 }
}
