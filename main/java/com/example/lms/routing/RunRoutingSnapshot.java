package com.example.lms.routing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable policy identity and server-resolved candidates for exactly one existing Run. */
public record RunRoutingSnapshot(int schemaVersion,long profileRevision,String profileHash,
        boolean runtimeEnabled,boolean profileEnabled,Map<RoutingProfile.Role,ResolvedBinding> bindings,String reasonCode) {
    public record Candidate(String target,String modelId,String provider,String endpointId,String endpointIdentityHash) {
        public Candidate(String target,String modelId,String provider,String endpointId){this(target,modelId,provider,endpointId,null);}
    }
    public record ResolvedBinding(RoutingProfile.Role role,Candidate primary,List<Candidate> orderedFallbacks,int maxExtraFallbackCalls) {
        public ResolvedBinding {orderedFallbacks=List.copyOf(orderedFallbacks);}
        public List<Candidate> candidates(){var list=new java.util.ArrayList<Candidate>();list.add(primary);list.addAll(orderedFallbacks);return List.copyOf(list);}
    }
    public RunRoutingSnapshot {bindings=Map.copyOf(bindings);}
    public static RunRoutingSnapshot disabled(){return new RunRoutingSnapshot(1,0,null,false,false,Map.of(),"routing_disabled");}
    public static RunRoutingSnapshot inherited(long revision,String hash){return new RunRoutingSnapshot(1,revision,hash,true,false,Map.of(),"profile_disabled");}
    public static RunRoutingSnapshot unavailable(){return new RunRoutingSnapshot(1,0,null,true,false,Map.of(),"routing_policy_unavailable");}
    public Optional<ResolvedBinding> binding(RoutingProfile.Role role){
        if("routing_policy_unavailable".equals(reasonCode))throw new RoutingProfileResolver.Unavailable();
        return runtimeEnabled && profileEnabled ? Optional.ofNullable(bindings.get(role)) : Optional.empty();
    }
}
