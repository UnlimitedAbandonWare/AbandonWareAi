package com.example.lms.routing;
import org.junit.jupiter.api.Test;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicInteger;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import static org.junit.jupiter.api.Assertions.*;
class RoutingFallbackBoundaryTest {
 @Test void boundSelectorKeepsLocalOrderAndNeverEvaluatesOutsideCandidate() throws Exception {
  var props=new ai.abandonware.nova.config.LlmRouterProperties();props.setEnabled(true);
  var a=cfg("gemma4:12b");var b=cfg("qwen3.5:9b");var outside=cfg("gemma4:26b");a.setFallbackKey("outside");
  props.setModels(new LinkedHashMap<>(Map.of("a",a,"b",b,"outside",outside)));
  var gateway=org.mockito.Mockito.mock(com.example.lms.llm.gateway.HybridLlmGatewayProbeService.class);
  org.mockito.Mockito.when(gateway.localFailoverEnabled()).thenReturn(true);org.mockito.Mockito.when(gateway.cloudFallbackEnabled()).thenReturn(true);
  org.mockito.Mockito.when(gateway.evaluate(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->{
   String key=call.getArgument(0);ai.abandonware.nova.config.LlmRouterProperties.ModelConfig config=call.getArgument(1);
   return com.example.lms.llm.gateway.RoutingEligibility.eligible(key,"local",config.getName(),"chat",100,false,Map.of());});
  String endpoint=com.example.lms.llm.ModelRuntimeHealthTracker.endpointIdentityHash(a.getBaseUrl());
  var inv=new RoutingInvocation(new RunRoutingSnapshot.ResolvedBinding(RoutingProfile.Role.MAIN_DEFAULT,new RunRoutingSnapshot.Candidate("llmrouter.a",a.getName(),"local","a",endpoint),List.of(new RunRoutingSnapshot.Candidate("llmrouter.b",b.getName(),"local","b",endpoint)),1));
  var aspect=new ai.abandonware.nova.orch.aop.LlmRouterAspect(new org.springframework.mock.env.MockEnvironment(),props,new ai.abandonware.nova.orch.router.LlmRouterBandit(props),new ai.abandonware.nova.config.NovaModelGuardProperties(),null,gateway,null,new com.example.lms.llm.gateway.LlmGatewayFailureClassifier());
  Class<?> type=Class.forName("ai.abandonware.nova.orch.aop.LlmRouterAspect$CallArgs");Object args=org.springframework.test.util.ReflectionTestUtils.invokeMethod(type,"parse",(Object)new Object[]{"llmrouter.a",.2,.8,null,null,96,2,0,null,inv});
  var selected=(ai.abandonware.nova.orch.router.LlmRouterBandit.Selected)org.springframework.test.util.ReflectionTestUtils.invokeMethod(aspect,"nextEligibleSelection",new ai.abandonware.nova.orch.router.LlmRouterBandit.Selected("a",a),args,Set.of("a"),false);
  assertNotNull(selected);assertEquals("b",selected.key());
  org.mockito.Mockito.verify(gateway,org.mockito.Mockito.never()).evaluate(org.mockito.ArgumentMatchers.eq("outside"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
  assertFalse(inv.matchesEndpoint("llmrouter.b","different"));assertTrue(inv.matchesEndpoint("llmrouter.b",endpoint));
 }
 static ai.abandonware.nova.config.LlmRouterProperties.ModelConfig cfg(String model){var c=new ai.abandonware.nova.config.LlmRouterProperties.ModelConfig();c.setEnabled(true);c.setProvider("local");c.setStage("chat");c.setName(model);c.setBaseUrl("http://127.0.0.1:19001/v1");return c;}
 static RunRoutingSnapshot.Candidate c(String id){return new RunRoutingSnapshot.Candidate("llmrouter."+id,id,"local",id);}
 static RoutingInvocation invocation(int budget){return new RoutingInvocation(new RunRoutingSnapshot.ResolvedBinding(RoutingProfile.Role.MAIN_DEFAULT,c("a"),List.of(c("b")),budget));}
 @Test void zeroBudgetNeverCallsExtraOrOutsideCandidate(){
  var inv=invocation(0);var calls=new AtomicInteger();
  dev.langchain4j.model.chat.ChatModel fake=new com.example.lms.llm.NamedChatModel(){public String resolvedModelName(){return "fixture";}public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest req){calls.incrementAndGet();return ChatResponse.builder().aiMessage(AiMessage.from("synthetic")).build();}};
  assertThrows(IllegalArgumentException.class,()->inv.wrap(fake,c("outside"),true));
  var fallback=inv.wrap(fake,c("b"),true);
  assertThrows(IllegalStateException.class,()->fallback.chat(List.of(UserMessage.from("fixture"))));
  assertEquals(0,calls.get());assertEquals(0,inv.observation().attemptCount());
 }
 @Test void delayedConcurrentFallbackSharesOneBudget() throws Exception {
  var inv=invocation(1);var calls=new AtomicInteger();
  dev.langchain4j.model.chat.ChatModel fake=new com.example.lms.llm.NamedChatModel(){public String resolvedModelName(){return "fixture";}public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest req){calls.incrementAndGet();return ChatResponse.builder().aiMessage(AiMessage.from("fixture")).build();}};
  var fallback=inv.wrap(fake,c("b"),true);var pool=Executors.newFixedThreadPool(2);
  try{var a=pool.submit(()->{try{fallback.chat(List.of(UserMessage.from("fixture")));return true;}catch(IllegalStateException e){return false;}});
   var b=pool.submit(()->{try{fallback.chat(List.of(UserMessage.from("fixture")));return true;}catch(IllegalStateException e){return false;}});
   assertNotEquals(a.get(2,TimeUnit.SECONDS),b.get(2,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(1,inv.observation().extraFallbackCallCount());
  }finally{pool.shutdownNow();}
 }
 @Test void constructionIsNotObservedGeneration(){
  var inv=invocation(1);inv.wrap(org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class),c("a"),false);
  assertEquals(0,inv.observation().attemptCount());assertNull(inv.observation().responseModelId());
 }
}
