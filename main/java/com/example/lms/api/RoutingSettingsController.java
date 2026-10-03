package com.example.lms.api;

import com.example.lms.routing.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Three POST operations inherit the existing /api/settings ADMIN boundary and CSRF handling. */
@RestController
@RequestMapping("/api/settings/routing")
public class RoutingSettingsController {
    private final RoutingSettingsService service;
    private final RoutingProfileResolver resolver;
    private final SettingsCapabilityProjection capabilities;
    private RoutingOutcomeAccess outcomeAccess;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setOutcomeAccess(RoutingOutcomeAccess access){this.outcomeAccess=access;}
    public RoutingSettingsController(RoutingSettingsService service,RoutingProfileResolver resolver,
            SettingsCapabilityProjection capabilities,@org.springframework.lang.Nullable com.example.lms.service.chat.ChatRunRegistry registry){
        this.service=service;this.resolver=resolver;this.capabilities=capabilities;
    }
    @PostMapping("/read")
    public ResponseEntity<?> read(@RequestBody(required=false) String body,org.springframework.security.core.Authentication authentication){
        return respond(()->{
            var request=RoutingProfile.object(body==null?"{}":body);
            RoutingProfile.fields(request,Set.of("sessionId","runToken"));
            var state=service.read();var view=new LinkedHashMap<String,Object>();
            view.put("profileRevision",state.profileRevision());view.put("profileHash",state.profileHash());
            view.put("profile",state.profile());view.put("profileEnabled",state.profile().enabled());
            view.put("runtimeEnabled",resolver.runtimeEnabled());view.put("runtimeSupported",resolver.runtimeSupported());
            view.put("candidates",resolver.observedCandidates());
            view.put("settingsView",capabilities==null?List.of():capabilities.project(Map.of()));
            view.put("pipelineView",SettingsPlanProjection.fromMetadata(Map.of()));view.put("outcome",null);
            if(request.hasNonNull("sessionId") || request.hasNonNull("runToken")){
                long sessionId=RoutingProfile.integer(request.get("sessionId"),Long.MAX_VALUE-1);
                if(sessionId<=0 || !request.path("runToken").isTextual())throw new IllegalArgumentException();
                String token=request.path("runToken").textValue();
                var outcome=outcomeAccess==null?null:outcomeAccess.read(sessionId,token,authentication).orElse(null);
                view.put("outcome",outcome);
                if(outcome!=null && outcome.pipelineView()!=null)view.put("pipelineView",outcome.pipelineView());
            }
            return view;
        });
    }
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody String body){
        return respond(()->{
            var node=RoutingProfile.object(body);RoutingProfile.fields(node,Set.of("profile"));
            var profile=RoutingProfile.parse(node.path("profile").toString());
            var view=new LinkedHashMap<String,Object>();
            view.put("effectiveBindings",resolver.validate(profile));view.put("externalCalls",0);view.put("writes",0);
            view.put("runtimeEnabled",resolver.runtimeEnabled());view.put("responseModelId",null);view.put("costEstimateUsd",null);
            return view;
        });
    }
    @PostMapping("/save")
    public ResponseEntity<?> save(@RequestBody String body){
        return respond(()->{
            var node=RoutingProfile.object(body);RoutingProfile.fields(node,Set.of("expectedRevision","expectedProfileHash","profile"));
            long revision=RoutingProfile.integer(node.get("expectedRevision"),Long.MAX_VALUE-1);
            if(node.hasNonNull("expectedProfileHash") && !node.get("expectedProfileHash").isTextual())throw new IllegalArgumentException();
            String hash=node.hasNonNull("expectedProfileHash")?node.get("expectedProfileHash").textValue():null;
            if(hash!=null && !hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            var profile=RoutingProfile.parse(node.path("profile").toString());resolver.validate(profile);
            return service.save(profile,revision,hash);
        });
    }
    private ResponseEntity<?> respond(java.util.function.Supplier<Object> action){
        try{return ResponseEntity.ok(action.get());}
        catch(RoutingSettingsService.Conflict e){return ResponseEntity.status(409).body(Map.of("reasonCode","revision_conflict"));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("reasonCode","invalid_routing_request"));}
        catch(RuntimeException e){return ResponseEntity.status(503).body(Map.of("reasonCode","routing_policy_unavailable"));}
    }
}
