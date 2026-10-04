package com.example.lms.routing;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class RoutingOutcomeProjectionTest{
 static com.example.lms.service.chat.ChatRunRegistry.RunOutcomeView terminal(boolean finalAccepted){return new com.example.lms.service.chat.ChatRunRegistry.RunOutcomeView("a".repeat(64),"succeeded",true,true,true,false,true,1,"accepted",finalAccepted,"not_observed",0,"completed",1,20);}
 @Test void auxiliaryLastResponseNeverBecomesMainModel(){
  var observations=List.of(new RoutingInvocation.Observation(RoutingProfile.Role.MAIN_DEFAULT,1,0,"main-model","llmrouter.main",null,true),
   new RoutingInvocation.Observation(RoutingProfile.Role.SELFASK_RC,2,1,"aux-model","llmrouter.aux",null,true));
  var view=RoutingOutcomeProjector.project(RunRoutingSnapshot.disabled(),observations,terminal(true));
  assertEquals("main-model",view.mainResponseModelId());assertTrue(view.mainFinalAdopted());assertEquals(RoutingProfile.Role.values().length,view.roles().size(),"Projection must contain every routing role");
 }
 @Test void unobservedAndAmbiguousMainStayNull(){
  var view=RoutingOutcomeProjector.project(RunRoutingSnapshot.disabled(),List.of(),terminal(true));assertNull(view.mainResponseModelId());assertFalse(view.mainFinalAdopted());
  var ambiguous=List.of(new RoutingInvocation.Observation(RoutingProfile.Role.MAIN_DEFAULT,1,0,"m1","llmrouter.a",null,true),new RoutingInvocation.Observation(RoutingProfile.Role.MAIN_HIGH,1,0,"m2","llmrouter.b",null,true));
  assertNull(RoutingOutcomeProjector.project(RunRoutingSnapshot.disabled(),ambiguous,terminal(true)).mainResponseModelId());
 }
}
