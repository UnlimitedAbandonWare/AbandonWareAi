package com.example.lms.routing;
import org.junit.jupiter.api.Test;import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RoutingFactoryArgumentsTest {
 @Test void tenArgumentParseKeepsSamplingRetriesContextAndInvocation() throws Exception{
  Class<?> ca=Class.forName("ai.abandonware.nova.orch.aop.LlmRouterAspect$CallArgs");
  var inv=RoutingFallbackBoundaryTest.invocation(0);
  var context=com.example.lms.llm.spec.ModelSpecSnapshot.of("local","a","127.0.0.1",4096,null,List.of(),Map.of());
  Object[] args={"llmrouter.a",.23,.71,.12,.34,777,9,0,context,inv};
  Object parsed=ReflectionTestUtils.invokeMethod(ca,"parse",(Object)args);
  assertNotNull(parsed);assertEquals(.23,ReflectionTestUtils.getField(parsed,"temperature"));
  assertEquals(.71,ReflectionTestUtils.getField(parsed,"topP"));assertEquals(.12,ReflectionTestUtils.getField(parsed,"frequencyPenalty"));
  assertEquals(.34,ReflectionTestUtils.getField(parsed,"presencePenalty"));assertEquals(777,ReflectionTestUtils.getField(parsed,"maxTokens"));
  assertEquals(9,ReflectionTestUtils.getField(parsed,"timeoutSeconds"));assertEquals(0,ReflectionTestUtils.getField(parsed,"maxRetriesOverride"));
  assertSame(inv,ReflectionTestUtils.getField(parsed,"routingInvocation"));assertSame(context,ReflectionTestUtils.getField(parsed,"observedContext"));
  Object copy=ReflectionTestUtils.invokeMethod(Class.forName("ai.abandonware.nova.orch.aop.LlmRouterAspect"),"withoutLibraryRetries",parsed);
  assertSame(inv,ReflectionTestUtils.getField(copy,"routingInvocation"));assertEquals(.23,ReflectionTestUtils.getField(copy,"temperature"));
  assertSame(context,ReflectionTestUtils.getField(copy,"observedContext"));
  assertEquals(4096,com.example.lms.llm.DynamicChatModelFactory.validatedContextCapacity(context,"a","http://127.0.0.1:1/v1"));
  assertNull(com.example.lms.llm.DynamicChatModelFactory.validatedContextCapacity(context,"different","http://127.0.0.1:1/v1"));
 }
 @Test void existingObservedContextOverloadStaysDistinct() throws Exception{
  var cls=com.example.lms.llm.DynamicChatModelFactory.class;
  assertNotNull(cls.getMethod("lcWithTimeout",String.class,Double.class,Double.class,Double.class,Double.class,Integer.class,int.class,Integer.class,com.example.lms.llm.spec.ModelSpecSnapshot.class));
  assertNotNull(cls.getMethod("lcWithTimeout",String.class,Double.class,Double.class,Double.class,Double.class,Integer.class,int.class,Integer.class,com.example.lms.llm.spec.ModelSpecSnapshot.class,RoutingInvocation.class));
 }
}
