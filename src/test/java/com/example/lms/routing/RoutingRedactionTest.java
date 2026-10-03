package com.example.lms.routing;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class RoutingRedactionTest{
 @Test void onlyAllowedIdentifiersAndFixedReasonsReachProjection() throws Exception{
  var observation=new RoutingInvocation.Observation(RoutingProfile.Role.SELFASK_RC,1,0,"api_key=PRIVATE secret","https://user:PRIVATE@host", "private exception payload",true);
  String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(RoutingOutcomeProjector.project(RunRoutingSnapshot.disabled(),List.of(observation),RoutingOutcomeProjectionTest.terminal(false)));
  assertFalse(json.contains("PRIVATE"));assertFalse(json.contains("private exception"));assertFalse(json.contains("https:"));assertTrue(json.contains("not_observed"));
 }
}
