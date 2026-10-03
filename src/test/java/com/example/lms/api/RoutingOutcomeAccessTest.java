package com.example.lms.api;
import org.junit.jupiter.api.Test;import org.springframework.http.ResponseEntity;import java.util.*;
import com.example.lms.service.chat.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class RoutingOutcomeAccessTest{
 @Test void authorizedExactRunReadsOnlyItsTypedPipeline(){
  var registry=new ChatRunRegistry();org.springframework.test.util.ReflectionTestUtils.setField(registry,"replayCapacity",512);
  try{var run=registry.beginOrJoin(51L).context();String runReference=registry.currentRunToken(51L).orElseThrow();
   var pipeline=ChatStreamSignalBuilder.buildPipelineSnapshot(Map.of("plan.when.present",true,"plan.when","true"),null,null,null);
   assertTrue(registry.emit(run,org.springframework.http.codec.ServerSentEvent.builder(com.example.lms.dto.ChatStreamEvent.trace("",null,pipeline)).build()));
   var state=mock(ChatApiController.class);when(state.state(51L,true,runReference,null)).thenReturn(ResponseEntity.ok(Map.of("runIdentityHash",run.redactedRunIdentity(),"lastAssistant","PRIVATE")));
   var view=new RoutingOutcomeAccess(state,registry).read(51L,runReference,null).orElseThrow();
   assertEquals("true",view.pipelineView().whenState());assertNull(view.mainResponseModelId());
   assertFalse(view.toString().contains("PRIVATE"));
   when(state.state(51L,true,runReference,null)).thenReturn(ResponseEntity.ok(Map.of("runIdentityHash","hash:"+"b".repeat(12))));
   assertTrue(new RoutingOutcomeAccess(state,registry).read(51L,runReference,null).isEmpty());
  }finally{org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry,"shutdown");}
 }
 @Test void neutralOrForeignStateCannotReadRunRouting(){
  var state=mock(ChatApiController.class);var registry=mock(ChatRunRegistry.class);when(state.state(11L,true,"fixture",null)).thenReturn(ResponseEntity.ok(Map.of("runStatus","missing_or_replaced")));
  assertTrue(new RoutingOutcomeAccess(state,registry).read(11L,"fixture",null).isEmpty());verifyNoInteractions(registry);
 }
 @Test void exactAuthorizedHashRequiredBeforeProjection(){
  var state=mock(ChatApiController.class);var registry=mock(ChatRunRegistry.class);
  when(state.state(11L,true,"fixture",null)).thenReturn(ResponseEntity.ok(Map.of("runIdentityHash","hash:"+"a".repeat(12),"lastAssistant","PRIVATE")));
  when(registry.routingViewForAuthorizedExact(11L,"fixture")).thenReturn(Optional.empty());
  assertTrue(new RoutingOutcomeAccess(state,registry).read(11L,"fixture",null).isEmpty());verify(registry).routingViewForAuthorizedExact(11L,"fixture");
 }
 @Test void failedOwnerResponseCannotAuthorizeEvenWithAHash(){
  var state=mock(ChatApiController.class);var registry=mock(ChatRunRegistry.class);
  when(state.state(11L,true,"fixture",null)).thenReturn(ResponseEntity.status(403).body(Map.of("runIdentityHash","hash:"+"a".repeat(12))));
  assertTrue(new RoutingOutcomeAccess(state,registry).read(11L,"fixture",null).isEmpty());verifyNoInteractions(registry);
 }
}
