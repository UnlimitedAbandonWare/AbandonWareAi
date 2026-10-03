package com.example.lms.routing;
import com.example.lms.service.chat.*;import com.example.lms.service.routing.*;import com.example.lms.llm.*;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.ObjectProvider;import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
class SettingsRoutingIntegrationTest {
 @Test void fastAndHighConsumersKeepTheirOwnRole(){
  for(boolean high:List.of(false,true)){
   var factory=mock(DynamicChatModelFactory.class);var base=mock(dev.langchain4j.model.chat.ChatModel.class);var other=mock(dev.langchain4j.model.chat.ChatModel.class);var highModel=mock(dev.langchain4j.model.chat.ChatModel.class);
   ObjectProvider<dev.langchain4j.model.chat.ChatModel> fast=mock(ObjectProvider.class),quality=mock(ObjectProvider.class);
   when(fast.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(other);when(quality.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(highModel);
   var policy=mock(RouterPolicy.class);when(policy.shouldPromote(any())).thenReturn(high);when(factory.canServe(any())).thenReturn(true);
   var router=new PolicyBasedModelRouter(base,fast,quality,policy,factory);ReflectionTestUtils.setField(router,"fastTimeoutSeconds",3);ReflectionTestUtils.setField(router,"highTimeoutSeconds",30);
   var role=high?RoutingProfile.Role.MAIN_HIGH:RoutingProfile.Role.MAIN_FAST;var binding=new RunRoutingSnapshot.ResolvedBinding(role,RoutingFallbackBoundaryTest.c("a"),List.of(),0);
   var resolver=mock(RoutingProfileResolver.class);when(resolver.capture()).thenReturn(new RunRoutingSnapshot(1,1,"hash",true,true,Map.of(role,binding),null));
   var registry=new ChatRunRegistry();registry.setRoutingProfileResolver(resolver);ReflectionTestUtils.setField(registry,"replayCapacity",512);
   var selected=mock(dev.langchain4j.model.chat.ChatModel.class);
   when(factory.lcWithTimeout(eq("llmrouter.a"),anyDouble(),isNull(),isNull(),isNull(),eq(512),eq(high?30:3),eq(0),isNull(),any(RoutingInvocation.class))).thenReturn(selected);
   try{try(var scope=ChatRunExecutionContext.bind(registry.beginOrJoin(925L).context())){
    assertSame(selected,router.routeMain(high?"CHAT":"REWRITE","LOW","NORMAL",512,null,"synthetic",false));
    verify(factory).lcWithTimeout(eq("llmrouter.a"),anyDouble(),isNull(),isNull(),isNull(),eq(512),eq(high?30:3),eq(0),isNull(),any(RoutingInvocation.class));
   }}finally{ReflectionTestUtils.invokeMethod(registry,"shutdown");com.example.lms.search.TraceStore.clear();}
  }
 }
 @Test void mainUsesRoleAfterExistingDecisionWithoutCachingRunWrapper(){
  var factory=mock(DynamicChatModelFactory.class);var base=mock(dev.langchain4j.model.chat.ChatModel.class);
  var bound=mock(dev.langchain4j.model.chat.ChatModel.class);var router=new PolicyBasedModelRouter(base,null,null,null,factory);
  ReflectionTestUtils.setField(router,"timeoutSeconds",12);
  var binding=new RunRoutingSnapshot.ResolvedBinding(RoutingProfile.Role.MAIN_DEFAULT,RoutingFallbackBoundaryTest.c("a"),List.of(),0);
  var resolver=mock(RoutingProfileResolver.class);when(resolver.capture()).thenReturn(new RunRoutingSnapshot(1,1,"hash",true,true,Map.of(binding.role(),binding),null));
  var registry=new ChatRunRegistry();registry.setRoutingProfileResolver(resolver);ReflectionTestUtils.setField(registry,"replayCapacity",512);
  when(factory.lcWithTimeout(eq("llmrouter.a"),anyDouble(),isNull(),isNull(),isNull(),eq(512),eq(12),eq(0),isNull(),any(RoutingInvocation.class))).thenReturn(bound);
  try{var run=registry.beginOrJoin(923L).context();try(var ignored=ChatRunExecutionContext.bind(run)){
    assertSame(bound,router.routeMain("CHAT","LOW","NORMAL",512,null,"synthetic",false));
    assertSame(bound,router.routeMain("CHAT","LOW","NORMAL",512,null,"synthetic",false));}
   verify(factory,times(2)).lcWithTimeout(eq("llmrouter.a"),anyDouble(),isNull(),isNull(),isNull(),eq(512),eq(12),eq(0),isNull(),same(run.routingInvocation(binding.role()).orElseThrow()));
   verifyNoInteractions(base,bound);
  }finally{ReflectionTestUtils.invokeMethod(registry,"shutdown");com.example.lms.search.TraceStore.clear();}
 }
 @Test void unboundMainKeepsExistingClient(){
  var base=mock(dev.langchain4j.model.chat.ChatModel.class);var factory=mock(DynamicChatModelFactory.class);
  assertSame(base,new PolicyBasedModelRouter(base,null,null,null,factory).routeMain("CHAT","LOW","NORMAL",512,null,"synthetic",false));verifyNoInteractions(factory,base);com.example.lms.search.TraceStore.clear();
 }
}
