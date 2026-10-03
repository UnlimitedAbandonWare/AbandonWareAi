package com.example.lms.routing;
import com.example.lms.service.chat.ChatRunRegistry;
import java.util.*;
/** Typed same-run observations only; no history, prompt, exception or modelUsed mutation. */
public final class RoutingOutcomeProjector {
 private RoutingOutcomeProjector(){}
 public record RoleOutcome(RoutingProfile.Role role,String configuredTarget,String configuredModelId,
  String configuredProvider,String effectiveTarget,String responseModelId,Integer attemptCount,Integer extraFallbackCallCount,String reasonCode){}
 public record View(int schemaVersion,long profileRevision,String profileHash,String runIdentityHash,
  boolean runtimeEnabled,boolean profileEnabled,List<RoleOutcome> roles,String mainResponseModelId,boolean mainFinalAdopted,
  com.example.lms.api.SettingsPlanProjection.View pipelineView){}
 public static View withPipeline(View view,com.example.lms.api.SettingsPlanProjection.View plan){
  return new View(view.schemaVersion(),view.profileRevision(),view.profileHash(),view.runIdentityHash(),view.runtimeEnabled(),
   view.profileEnabled(),view.roles(),view.mainResponseModelId(),view.mainFinalAdopted(),plan);
 }
 private static String id(String value){return RoutingProfile.identifier(value)?value:null;}
 public static View project(RunRoutingSnapshot snapshot,List<RoutingInvocation.Observation> observations,ChatRunRegistry.RunOutcomeView outcome){
  Map<RoutingProfile.Role,RoutingInvocation.Observation> byRole=new EnumMap<>(RoutingProfile.Role.class);
  for(var observation:observations)byRole.put(observation.role(),observation);
  List<RoleOutcome> roles=new ArrayList<>();
  for(var role:RoutingProfile.Role.values()){
   var binding=snapshot.bindings().get(role);var observation=byRole.get(role);
   var configured=binding==null?null:binding.primary();
   roles.add(new RoleOutcome(role,configured==null?null:id(configured.target()),configured==null?null:id(configured.modelId()),
    configured==null?null:id(configured.provider()),observation==null?null:id(observation.selectedTarget()),
    observation==null?null:id(observation.responseModelId()),observation==null?null:Math.max(0,observation.attemptCount()),
    observation==null?null:Math.max(0,observation.extraFallbackCallCount()),
    observation==null || !Set.of("generation_failed","not_observed").contains(String.valueOf(observation.reasonCode()))?"not_observed":observation.reasonCode()));
  }
  var main=observations.stream().filter(o->o.role().name().startsWith("MAIN_")&&o.succeeded()&&id(o.responseModelId())!=null).toList();
  boolean adopted=main.size()==1 && outcome!=null && outcome.generationSucceeded()
      && (outcome.persisted()||outcome.finalDeliveryAccepted());
  String hash=snapshot.profileHash()!=null&&snapshot.profileHash().matches("[a-f0-9]{64}")?snapshot.profileHash():null;
  return new View(1,snapshot.profileRevision(),hash,outcome==null?null:outcome.runIdentityHash(),
   snapshot.runtimeEnabled(),snapshot.profileEnabled(),List.copyOf(roles),main.size()==1?id(main.get(0).responseModelId()):null,adopted,null);
 }
}
