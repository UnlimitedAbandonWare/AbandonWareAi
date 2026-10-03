package com.example.lms.api;
import org.junit.jupiter.api.Test;import com.example.lms.routing.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class SettingsOutcomeProjectionTest{
 @Test void readWithoutExactRunDoesNotRequestHistoryOrOutcome(){
  var service=mock(RoutingSettingsService.class);var resolver=mock(RoutingProfileResolver.class);var access=mock(RoutingOutcomeAccess.class);
  when(service.read()).thenReturn(new RoutingSettingsService.State(0,null,RoutingProfile.empty()));
  var controller=new RoutingSettingsController(service,resolver,null,null);controller.setOutcomeAccess(access);
  var result=controller.read("{}",null);assertEquals(200,result.getStatusCode().value());assertNull(((Map<?,?>)result.getBody()).get("outcome"));verifyNoInteractions(access);
 }
 @Test void readRejectsUnrelatedFieldsWithoutCallingOwnerGate(){
  var service=mock(RoutingSettingsService.class);var resolver=mock(RoutingProfileResolver.class);var access=mock(RoutingOutcomeAccess.class);
  var controller=new RoutingSettingsController(service,resolver,null,null);controller.setOutcomeAccess(access);
  assertEquals(400,controller.read("{\"query\":\"PRIVATE\"}",null).getStatusCode().value());verifyNoInteractions(service,access);
 }
}
