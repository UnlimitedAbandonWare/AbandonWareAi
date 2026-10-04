package com.example.lms.routing;

import com.example.lms.service.ChatModelCatalogService;
import com.example.lms.llm.ChatGptOAuthRegistration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** Static validation against already-observed server choices. Never refreshes, enables or probes a route. */
@Component
public class RoutingProfileResolver {
    public static final class Unavailable extends RuntimeException {public Unavailable(){super("routing_policy_unavailable");}}
    private final RoutingSettingsService service;
    private final ChatModelCatalogService catalog;
    private final boolean enabled;
    @Value("${chat.settings.routing.aux-paid.enabled:false}")
    private boolean auxPaidEnabled;
    public RoutingProfileResolver(RoutingSettingsService service,ChatModelCatalogService catalog,
            @Value("${chat.settings.routing.enabled:false}") boolean enabled){
        this.service=service;this.catalog=catalog;this.enabled=enabled;
    }
    public boolean runtimeEnabled(){return enabled;}
    private ai.abandonware.nova.config.LlmRouterProperties routerProperties;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setRouterProperties(ai.abandonware.nova.config.LlmRouterProperties properties){this.routerProperties=properties;}
    public boolean runtimeSupported(){return routerProperties!=null && routerProperties.isEnabled();}
    public List<ChatModelCatalogService.Choice> observedCandidates(){
        return catalog.observedServerChoices().stream().filter(c->c.selectable()
            && c.id().startsWith("llmrouter.")
            && RoutingProfile.identifier(c.id()) && RoutingProfile.identifier(c.modelId())
            && !ChatGptOAuthRegistration.isRoute(c.id())
            && !ChatGptOAuthRegistration.PROVIDER.equals(c.provider())).toList();
    }
    public Map<RoutingProfile.Role,RunRoutingSnapshot.ResolvedBinding> validate(RoutingProfile profile){
        if(profile.bindings().isEmpty())return Map.of();
        Map<String,ChatModelCatalogService.Choice> choices=new HashMap<>();
        for(var c:observedCandidates())choices.put(c.id(),c);
        Map<RoutingProfile.Role,RunRoutingSnapshot.ResolvedBinding> bindings=new EnumMap<>(RoutingProfile.Role.class);
        profile.bindings().forEach((role,b)->{
            var primary=candidate(choices,b.target());var fallbacks=b.orderedFallbacks().stream().map(id->candidate(choices,id)).toList();
            for(var candidate:java.util.stream.Stream.concat(java.util.stream.Stream.of(primary),fallbacks.stream()).toList()){
                if(!candidate.supports(role))throw new IllegalArgumentException("route_capability_unverified");
                if(role.auxiliary() && candidate.paid()){
                    var price=candidate.price();
                    if(!b.auxPaidAllowed() || price==null || !price.fresh())
                        throw new IllegalArgumentException("auxiliary_price_or_opt_in_unverified");
                    var first=primary.price();
                    if(first==null || price.input().compareTo(first.input())>0 || price.output().compareTo(first.output())>0)
                        throw new IllegalArgumentException("auxiliary_cost_escalation_forbidden");
                }
            }
            bindings.put(role,new RunRoutingSnapshot.ResolvedBinding(role,primary,fallbacks,b.maxExtraFallbackCalls(),
                    auxPaidEnabled && b.auxPaidAllowed(),b.auxCostCapUsdPerRun()));
        });
        return Map.copyOf(bindings);
    }
    private RunRoutingSnapshot.Candidate candidate(Map<String,ChatModelCatalogService.Choice> choices,String id){
        var c=choices.get(id);if(c==null)throw new IllegalArgumentException("route_not_observed_or_unavailable");
        var cfg=routerProperties==null?null:routerProperties.getModels().get(id.substring("llmrouter.".length()));
        if(cfg==null || !cfg.isEnabled() || !c.modelId().equals(cfg.getName()))throw new IllegalArgumentException("route_not_observed_or_unavailable");
        String endpointHash=com.example.lms.llm.ModelRuntimeHealthTracker.endpointIdentityHash(cfg.getBaseUrl());
        if("unknown".equals(endpointHash))throw new IllegalArgumentException("route_endpoint_unavailable");
        return new RunRoutingSnapshot.Candidate(c.id(),c.modelId(),c.provider(),c.endpointId(),endpointHash,c.metadata());
    }
    public RunRoutingSnapshot capture(){
        if(!enabled)return RunRoutingSnapshot.disabled();
        try{
            var state=service.read();
            if(!state.profile().enabled())return RunRoutingSnapshot.inherited(state.profileRevision(),state.profileHash());
            return new RunRoutingSnapshot(1,state.profileRevision(),state.profileHash(),true,true,validate(state.profile()),null);
        }catch(RuntimeException failure){throw new Unavailable();}
    }
}
