package com.example.lms.routing;
import com.example.lms.service.chat.*;import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class RoleRoutingConsumptionTest {
 @Test void selfAskConsumersCarryAllThreeBoundModelsAndProviders(){
  var factory=mock(com.example.lms.llm.DynamicChatModelFactory.class);
  org.springframework.beans.factory.ObjectProvider<com.example.lms.llm.DynamicChatModelFactory> provider=mock(org.springframework.beans.factory.ObjectProvider.class);
  when(provider.getIfAvailable()).thenReturn(factory);
  var baseline=mock(dev.langchain4j.model.chat.ChatModel.class);
  var planner=new com.example.lms.service.rag.SelfAskPlanner(baseline,provider);
  var bindings=new EnumMap<RoutingProfile.Role,RunRoutingSnapshot.ResolvedBinding>(RoutingProfile.Role.class);
  for(var role:List.of(RoutingProfile.Role.SELFASK_BQ,RoutingProfile.Role.SELFASK_ER,RoutingProfile.Role.SELFASK_RC))
   bindings.put(role,new RunRoutingSnapshot.ResolvedBinding(role,RoutingFallbackBoundaryTest.c(role.name()),List.of(),0));
  var resolver=mock(RoutingProfileResolver.class);when(resolver.capture()).thenReturn(new RunRoutingSnapshot(1,4,"hash",true,true,bindings,null));
  var registry=new ChatRunRegistry();registry.setRoutingProfileResolver(resolver);ReflectionTestUtils.setField(registry,"replayCapacity",512);
  when(factory.lcWithTimeout(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.eq(.8d),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq(96),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.any(RoutingInvocation.class)))
   .thenAnswer(call->new com.example.lms.llm.NamedChatModel(){public String resolvedModelName(){return "fixture";}
    public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request){return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("synthetic "+call.getArgument(0))).build();}});
  try{var run=registry.beginOrJoin(924L).context();try(var scope=ChatRunExecutionContext.bind(run)){
   var questions=planner.generateThreeLanes("synthetic",1000,.2d);assertEquals(3,questions.size());
   for(var role:bindings.keySet()){
    verify(factory).lcWithTimeout(org.mockito.ArgumentMatchers.eq(bindings.get(role).primary().target()),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.eq(.8d),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq(96),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.same(run.routingInvocation(role).orElseThrow()));
    var lane=com.example.lms.service.rag.SelfAskPlanner.SubQuestionType.valueOf(role.name().substring(8));
    assertEquals("local",ReflectionTestUtils.invokeMethod(planner,"providerForLane",lane,bindings.get(role).primary().target()));
   }
   verifyNoInteractions(baseline);
  }}finally{ReflectionTestUtils.invokeMethod(registry,"shutdown");com.example.lms.search.TraceStore.clear();}
 }
 @Test void sixRolesKeepTargetProviderAndRunBudget(){
  var bindings=new EnumMap<RoutingProfile.Role,RunRoutingSnapshot.ResolvedBinding>(RoutingProfile.Role.class);
  for(var role:RoutingProfile.Role.values())bindings.put(role,new RunRoutingSnapshot.ResolvedBinding(role,RoutingFallbackBoundaryTest.c(role.name()),List.of(),0));
  var snapshot=new RunRoutingSnapshot(1,8,"hash",true,true,bindings,null);var resolver=mock(RoutingProfileResolver.class);when(resolver.capture()).thenReturn(snapshot);
  var registry=new ChatRunRegistry();registry.setRoutingProfileResolver(resolver);ReflectionTestUtils.setField(registry,"replayCapacity",512);
  try{var run=registry.beginOrJoin(921L).context();
   for(var role:RoutingProfile.Role.values()){var a=run.routingInvocation(role).orElseThrow();assertSame(a,run.routingInvocation(role).orElseThrow());
    assertEquals("llmrouter."+role.name(),a.binding().primary().target());assertEquals("local",a.binding().primary().provider());}
   verify(resolver,times(1)).capture();
  }finally{ReflectionTestUtils.invokeMethod(registry,"shutdown");}
 }
 @Test void offRunHasNoRoleAndNoPolicyLookup(){
  var service=mock(RoutingSettingsService.class);var catalog=mock(com.example.lms.service.ChatModelCatalogService.class);
  var registry=new ChatRunRegistry();registry.setRoutingProfileResolver(new RoutingProfileResolver(service,catalog,false));ReflectionTestUtils.setField(registry,"replayCapacity",512);
  try{var run=registry.beginOrJoin(922L).context();for(var role:RoutingProfile.Role.values())assertTrue(run.routingInvocation(role).isEmpty());verifyNoInteractions(service,catalog);}
  finally{ReflectionTestUtils.invokeMethod(registry,"shutdown");}
 }
}
