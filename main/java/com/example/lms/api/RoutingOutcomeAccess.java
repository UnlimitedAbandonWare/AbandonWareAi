package com.example.lms.api;
import com.example.lms.routing.RoutingOutcomeProjector;
import com.example.lms.service.chat.ChatRunRegistry;
import org.springframework.stereotype.Component;import org.springframework.security.core.Authentication;import java.util.*;
/** Reuses the live /chat/state owner gate. Never interprets HTTP 200 as authorization. */
@Component
public class RoutingOutcomeAccess {
 private final ChatApiController state;private final ChatRunRegistry registry;
 public RoutingOutcomeAccess(ChatApiController state,ChatRunRegistry registry){this.state=state;this.registry=registry;}
 public Optional<RoutingOutcomeProjector.View> read(Long sessionId,String runToken,Authentication authentication){
  if(sessionId==null || sessionId<=0 || runToken==null || runToken.isBlank() || runToken.length()>256)return Optional.empty();
  var response=state.state(sessionId,true,runToken,authentication);
  if(!response.getStatusCode().is2xxSuccessful())return Optional.empty();
  var body=response.getBody();Object authorizedHash=body==null?null:body.get("runIdentityHash");
  if(!(authorizedHash instanceof String hash)||!hash.matches("hash:[a-f0-9]{12}"))return Optional.empty();
  return registry.routingViewForAuthorizedExact(sessionId,runToken)
   .filter(view->view.outcome()!=null && hash.equals(view.outcome().runIdentityHash()))
   .map(view->RoutingOutcomeProjector.withPipeline(RoutingOutcomeProjector.project(view.snapshot(),view.observations(),view.outcome()),view.pipelineView()));
 }
}
