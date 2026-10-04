package com.example.lms.routing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The bounded policy stored in one existing ConfigurationSetting. Explanation fields are never policy. */
public record RoutingProfile(int schemaVersion, long revision, boolean enabled, Map<Role,Binding> bindings,
                             boolean additionalPaidAllowed, BigDecimal additionalCostCapUsd) {
    public enum Role { MAIN_DEFAULT, MAIN_FAST, MAIN_HIGH, SELFASK_BQ, SELFASK_ER, SELFASK_RC, PROMPT_POSE_DRAFT;
        public boolean auxiliary(){return !name().startsWith("MAIN_");}
    }
    public record Binding(Role role, String selection, String target, List<String> orderedFallbacks,
                          int maxExtraFallbackCalls, boolean auxPaidAllowed, BigDecimal auxCostCapUsdPerRun) {
        public Binding { orderedFallbacks=List.copyOf(orderedFallbacks); }
        public Binding(Role role,String selection,String target,List<String> fallbacks,int extra) {
            this(role,selection,target,fallbacks,extra,false,BigDecimal.ZERO);
        }
    }
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public RoutingProfile { bindings=Collections.unmodifiableMap(new TreeMap<>(bindings)); }
    public static RoutingProfile empty(){return new RoutingProfile(1,0,false,Map.of(),false,BigDecimal.ZERO);}
    public RoutingProfile atRevision(long value){return new RoutingProfile(schemaVersion,value,enabled,bindings,additionalPaidAllowed,additionalCostCapUsd);}
    public String json(){try{return JSON.writeValueAsString(this);}catch(Exception e){throw invalid();}}
    public static JsonNode object(String text) {
        if(text==null || text.getBytes(StandardCharsets.UTF_8).length>16384)throw invalid();
        try{JsonNode node=JSON.readTree(text);if(node==null || !node.isObject())throw invalid();return node;}
        catch(Exception e){throw invalid();}
    }
    public static void fields(JsonNode node,Set<String> names){
        if(node==null || !node.isObject())throw invalid();
        node.fieldNames().forEachRemaining(k->{if(!names.contains(k))throw invalid();});
    }
    public static long integer(JsonNode node,long max){
        if(node==null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<0 || node.longValue()>max)throw invalid();
        return node.longValue();
    }
    public static boolean identifier(String value){
        return value!=null && value.length()<=256 && value.matches("[A-Za-z0-9][A-Za-z0-9._/:+-]*")
            && !value.contains("://") && !value.matches("(?i)^(sk-|AIza|eyJ|Bearer).*");
    }
    public static RoutingProfile parse(String text){
        JsonNode root=object(text);
        fields(root,Set.of("schemaVersion","revision","enabled","bindings","additionalPaidAllowed","additionalCostCapUsd"));
        if(integer(root.get("schemaVersion"),1)!=1 || !root.path("enabled").isBoolean()
                || !root.path("additionalPaidAllowed").isBoolean() || root.path("additionalPaidAllowed").booleanValue()
                || !root.path("additionalCostCapUsd").isNumber() || root.path("additionalCostCapUsd").decimalValue().signum()!=0)throw invalid();
        fields(root.get("bindings"),Set.of(Arrays.stream(Role.values()).map(Enum::name).toArray(String[]::new)));
        Map<Role,Binding> bindings=new EnumMap<>(Role.class);
        root.get("bindings").fields().forEachRemaining(entry->{
            Role role=Role.valueOf(entry.getKey());JsonNode b=entry.getValue();
            fields(b,Set.of("role","selection","target","orderedFallbacks","maxExtraFallbackCalls",
                    "auxPaidAllowed","auxCostCapUsdPerRun"));
            if(b.has("auxPaidAllowed") && !b.get("auxPaidAllowed").isBoolean())throw invalid();
            boolean paid=b.path("auxPaidAllowed").asBoolean(false);
            if(b.has("auxCostCapUsdPerRun") && !b.get("auxCostCapUsdPerRun").isNumber())throw invalid();
            BigDecimal cap=b.has("auxCostCapUsdPerRun")?b.get("auxCostCapUsdPerRun").decimalValue():BigDecimal.ZERO;
            if(cap.signum()<0 || cap.compareTo(new BigDecimal("0.05"))>0 || cap.scale()>8 || cap.precision()>8
                    || paid!= (cap.signum()>0) || !role.auxiliary() && paid)throw invalid();
            if(!role.name().equals(b.path("role").asText()) || !"registered-route".equals(b.path("selection").asText())
                    || !b.path("target").isTextual() || !identifier(b.path("target").textValue())
                    || !b.path("orderedFallbacks").isArray() || b.path("orderedFallbacks").size()>3)throw invalid();
            List<String> fallbacks=new ArrayList<>();
            for(JsonNode f:b.path("orderedFallbacks")){
                if(!f.isTextual() || !identifier(f.textValue()) || f.textValue().equals(b.path("target").textValue())
                        || fallbacks.contains(f.textValue()))throw invalid();
                fallbacks.add(f.textValue());
            }
            int extra=(int)integer(b.get("maxExtraFallbackCalls"),fallbacks.size());
            bindings.put(role,new Binding(role,"registered-route",b.path("target").textValue(),fallbacks,extra,paid,cap));
        });
        long revision=root.has("revision")?integer(root.get("revision"),Long.MAX_VALUE-1):0;
        return new RoutingProfile(1,revision,root.path("enabled").booleanValue(),bindings,false,BigDecimal.ZERO);
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid_routing_profile");}
}
