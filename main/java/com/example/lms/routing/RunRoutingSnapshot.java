package com.example.lms.routing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable policy identity and server-resolved candidates for exactly one existing Run. */
public record RunRoutingSnapshot(int schemaVersion,long profileRevision,String profileHash,
        boolean runtimeEnabled,boolean profileEnabled,Map<RoutingProfile.Role,ResolvedBinding> bindings,String reasonCode) {
    public record Candidate(String target,String modelId,String provider,String endpointId,String endpointIdentityHash,
                            Map<String,Object> metadata) {
        public Candidate {
            var copy=new java.util.HashMap<String,Object>(metadata==null?Map.of():metadata);
            for(String key:List.of("price","capabilityStatus","dataPolicy"))
                if(copy.get(key) instanceof Map<?,?> values)copy.put(key,Map.copyOf(values));
            if(copy.get("supportedRoles") instanceof java.util.Collection<?> values)
                copy.put("supportedRoles",List.copyOf(values));
            metadata=Map.copyOf(copy);
        }
        public Candidate(String target,String modelId,String provider,String endpointId,String endpointIdentityHash){
            this(target,modelId,provider,endpointId,endpointIdentityHash,Map.of());
        }
        public Candidate(String target,String modelId,String provider,String endpointId){this(target,modelId,provider,endpointId,null);}
        public boolean local(){return java.util.Set.of("local","ollama","ollama_chat","ollama_native",
                "local_openai_compatible","local_llm").contains(provider==null?"":provider.toLowerCase(java.util.Locale.ROOT));}
        public boolean paid(){return !local() && !"free".equals(metadata.get("costTier"));}
        public boolean supports(RoutingProfile.Role role){
            Object roles=metadata.get("supportedRoles");
            if(roles instanceof java.util.Collection<?> values && !values.contains(role.name()))return false;
            if(role.auxiliary() && !local() && (!(roles instanceof java.util.Collection<?> values)
                    || !values.contains(role.name())))return false;
            if(role.auxiliary() && !local()){
                Object caps=metadata.get("capabilityStatus");
                if(!(caps instanceof Map<?,?> values) || !"YES".equals(values.get("chat")))return false;
            }
            if(role==RoutingProfile.Role.PROMPT_POSE_DRAFT){
                Object caps=metadata.get("capabilityStatus");
                return caps instanceof Map<?,?> values && "YES".equals(values.get("schema"));
            }
            return true;
        }
        public Price price(){return Price.from(metadata.get("price"));}
    }
    public record Price(java.math.BigDecimal input,java.math.BigDecimal output,java.time.Instant checkedAt,
                        String sourceUrl,String priceVersion) {
        public static Price from(Object value){
            if(!(value instanceof Map<?,?> p) || !"USD".equals(p.get("currency"))
                    || !"per_1M_tokens".equals(p.get("unit")))return null;
            try{
                var input=new java.math.BigDecimal(String.valueOf(p.get("input")));
                var output=new java.math.BigDecimal(String.valueOf(p.get("output")));
                String source=String.valueOf(p.get("sourceUrl")), version=String.valueOf(p.get("priceVersion"));
                if(input.signum()<0 || output.signum()<0 || input.precision()>18 || output.precision()>18
                        || Math.abs(input.scale())>10 || Math.abs(output.scale())>10
                        || !source.startsWith("https://") || !RoutingProfile.identifier(version))return null;
                return new Price(input,output,java.time.Instant.parse(String.valueOf(p.get("checkedAt"))),source,version);
            }catch(RuntimeException invalid){return null;}
        }
        public boolean fresh(){
            var now=java.time.Instant.now();
            return checkedAt!=null && !checkedAt.isBefore(now.minus(java.time.Duration.ofHours(24)))
                    && !checkedAt.isAfter(now.plusSeconds(300));
        }
    }
    public record ResolvedBinding(RoutingProfile.Role role,Candidate primary,List<Candidate> orderedFallbacks,
                                 int maxExtraFallbackCalls,boolean auxPaidEnabled,java.math.BigDecimal auxCostCapUsdPerRun) {
        public ResolvedBinding {orderedFallbacks=List.copyOf(orderedFallbacks);}
        public ResolvedBinding(RoutingProfile.Role role,Candidate primary,List<Candidate> fallbacks,int extra){
            this(role,primary,fallbacks,extra,false,java.math.BigDecimal.ZERO);
        }
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
