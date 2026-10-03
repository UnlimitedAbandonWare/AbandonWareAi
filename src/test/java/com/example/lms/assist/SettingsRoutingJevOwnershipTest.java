package com.example.lms.assist;
import com.example.lms.routing.*;
import com.example.lms.service.chat.*;
import com.example.lms.service.routing.PolicyBasedModelRouter;
import com.example.lms.service.rag.SelfAskPlanner;
import com.example.lms.llm.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class SettingsRoutingJevOwnershipTest {
 @Test void roleConsumptionAndRegenerationReuseTheParentJevDispatch(){
  var factory=mock(DynamicChatModelFactory.class);
  var base=mock(dev.langchain4j.model.chat.ChatModel.class);
  org.springframework.beans.factory.ObjectProvider<DynamicChatModelFactory> provider=mock(org.springframework.beans.factory.ObjectProvider.class);
  when(provider.getIfAvailable()).thenReturn(factory);
  var planner=new SelfAskPlanner(base,provider);var router=new PolicyBasedModelRouter(base,null,null,null,factory);
  var bindings=new EnumMap<RoutingProfile.Role,RunRoutingSnapshot.ResolvedBinding>(RoutingProfile.Role.class);
  for(var role:RoutingProfile.Role.values())bindings.put(role,new RunRoutingSnapshot.ResolvedBinding(role,new RunRoutingSnapshot.Candidate("llmrouter.a","fixture","local","a"),List.of(),0));
  var resolver=mock(RoutingProfileResolver.class);when(resolver.capture()).thenReturn(new RunRoutingSnapshot(1,1,"fixture",true,true,bindings,null));
  var registry=new ChatRunRegistry();registry.setRoutingProfileResolver(resolver);ReflectionTestUtils.setField(registry,"replayCapacity",512);
  when(factory.lcWithTimeout(anyString(),anyDouble(),any(),any(),any(),anyInt(),anyInt(),eq(0),isNull(),any(RoutingInvocation.class))).thenAnswer(call->new NamedChatModel(){
   public String resolvedModelName(){return "fixture";}
   public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request){return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("synthetic question")).build();}
  });
  com.example.lms.search.TraceStore.clear();
  try(var harness=new JevPrefetchContractTest.Harness()){
   var key=harness.key();var admission=harness.admission();
   try(var owner=JevDecisionScope.bind("main",key,admission);var run=ChatRunExecutionContext.bind(registry.beginOrJoin(931L).context())){
    var captured=JevDecisionScope.capture();var first=harness.prefetch(key,admission);
    assertEquals(1,harness.executor.tasks.size());harness.executor.runNext();assertEquals(1,harness.calls.get());
    assertNotNull(router.routeMain("CHAT","LOW","NORMAL",512,null,"synthetic",false));
    assertNotNull(router.routeMain("CHAT","LOW","NORMAL",512,null,"synthetic",false));
    assertEquals(3,planner.generateThreeLanes("synthetic",1000,.2d).size());
    assertTrue(planner.regenerateLane("synthetic",SelfAskPlanner.SubQuestionType.BQ,1000,.2d,1d).isPresent());
    try(var nested=JevDecisionScope.bind("focus",key,admission)){
     assertSame(captured,JevDecisionScope.capture());assertSame(first,harness.prefetch(key,admission));
    }
    assertEquals(1,harness.calls.get(),"role consumption must add zero Jev transport calls");
    assertTrue(harness.executor.tasks.isEmpty());verifyNoInteractions(base);
   }
  }finally{ReflectionTestUtils.invokeMethod(registry,"shutdown");com.example.lms.search.TraceStore.clear();}
 }
}
