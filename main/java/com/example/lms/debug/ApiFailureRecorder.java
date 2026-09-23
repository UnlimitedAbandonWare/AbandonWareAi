package com.example.lms.debug;

import com.example.lms.routing.ApiRoutingDebug;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** API incidents only: no requests, response text, credentials, or exception messages survive classification. */
@Component
public class ApiFailureRecorder {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ApiFailureRecorder.class);
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> PROVIDERS=Set.of("openai","gemini","groq","brave","naver","serpapi","tavily","deepgram","soniox","kakao","pinecone","upstash","supabase","anthropic","zai","vercel","opencode","local","unknown");
    private static final Set<String> CODES=Set.of("invalid_api_key","authentication_error","UNAUTHENTICATED","insufficient_quota","credit_balance_too_low","insufficient_balance","billing_hard_limit_reached","organization_usage_limit_exceeded","rate_limit_exceeded","rate_limit_error","RATE_LIMITED","QUOTA_LIMITED","SUBSCRIPTION_EXHAUSTED","SUBSCRIPTION_TOKEN_INVALID","SUBSCRIPTION_NOT_FOUND","model_not_found","model_not_allowed","model_permission_denied","permission_error","PERMISSION_DENIED","RESOURCE_EXHAUSTED","unsupported_country_region_territory","024","028","012","010",
            "json_invalid","schema_invalid","text_limit","output_limit","evidence_invalid","evidence_missing","unclassified");
    /** Post-response contract failures reuse the cue outputValidation vocabulary as the incident code. */
    private static final Set<String> VALIDATION_CODES=Set.of("json_invalid","schema_invalid","text_limit","output_limit","evidence_invalid","evidence_missing","unclassified");
    private final DebugEventStore events;
    private final Path path;
    private final Clock clock;
    private final Map<String,Incident> incidents=new LinkedHashMap<>();
    private final java.util.concurrent.ThreadPoolExecutor writer=new java.util.concurrent.ThreadPoolExecutor(
            1,1,5,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1),
            task->{var thread=new Thread(task,"api-failure-writer");thread.setDaemon(true);return thread;},
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile boolean persistenceHealthy=true;

    public record Classification(String category,String errorCode,String evidence) {
        Classification(String category,String errorCode){this(category,errorCode,"UNKNOWN");}
    }
    public record Incident(String provider,String model,Integer httpStatus,String category,String errorCode,
                           String firstSeen,String lastSeen,long count,
                           String scope,String evidence,long consecutive,
                           String lastSuccessAt,String recoveredAt,
                           String maskedBy,String maskedAt) {}
    /** Per provider|model outcome state; incident rows carry the values observed at write time. */
    private static final class ProviderState {
        long consecutive;String lastSuccessAt,recoveredAt;
    }
    private final Map<String,ProviderState> states=new HashMap<>();
    private static final Set<String> SCOPES=Set.of("llm","stt","search","local","other");
    private static final Set<String> EVIDENCE=Set.of("CONFIRMED","SUSPECTED","UNKNOWN");
    private static String scopeOf(String provider) {
        return switch(Objects.toString(provider,"")) {
            case "soniox","deepgram"->"stt";
            case "brave","naver","serpapi","tavily","kakao"->"search";
            case "local"->"local";
            case "pinecone","upstash","supabase"->"other";
            default->"llm";
        };
    }

    @Autowired
    public ApiFailureRecorder(DebugEventStore events,
        @Value("${lms.api-failures.path:var/abnadon/debug/api-failures.json}") String path) {
        this(events,Path.of(path),Clock.systemUTC());
    }
    ApiFailureRecorder(DebugEventStore events,Path path,Clock clock) {
        this.events=events;this.path=path.toAbsolutePath().normalize();this.clock=clock;
        load();
    }
    public synchronized List<Incident> snapshot() {
        return incidents.values().stream().sorted(Comparator.comparing(Incident::lastSeen).reversed()).toList();
    }
    public boolean persistenceHealthy(){return persistenceHealthy;}

    public synchronized Incident record(String provider,String model,int status,String errorBody,Throwable failure) {
        if(status>=200&&status<400)return null;
        if(cancelled(failure))return null;
        return recordClassified(provider,model,status,classify(status,errorBody,failure));
    }
    /** Existing ASR signals can conflate causes; never manufacture a provider code or HTTP status from them. */
    public Incident recordSignal(String provider,String model,String signal) {
        String category=switch(Objects.toString(signal,"")) {
            case "ASR_AUTH_FAILED"->"authentication_or_permission";
            case "ASR_QUOTA_EXCEEDED"->"quota_exhausted";
            case "ASR_RATE_LIMITED"->"rate_limit";
            case "ASR_TIMEOUT","ASR_FIRST_RESULT_TIMEOUT","ASR_STREAM_TIMEOUT"->"timeout";
            case "ASR_TRANSPORT_ERROR","ASR_SIDECAR_FAILED"->"connection";
            default->"unconfirmed";
        };
        return recordClassified(provider,model,0,new Classification(category,"unconfirmed",
                "unconfirmed".equals(category)?"UNKNOWN":"SUSPECTED"));
    }
    /** Validation failures happen after a 2xx response; no HTTP status is manufactured. */
    public synchronized Incident recordValidation(String provider,String model,String validationCode) {
        return recordClassified(provider,model,0,new Classification("validation",
                VALIDATION_CODES.contains(validationCode)?validationCode:"unclassified","CONFIRMED"));
    }
    /** Router-level failure classes: probe/manager observations are CONFIRMED, exception inference SUSPECTED. */
    public synchronized Incident recordFailureClass(String provider,String model,String failureClass) {
        Classification classified=switch(Objects.toString(failureClass,"")) {
            case "TIMEOUT_SOFT"->new Classification("timeout","unconfirmed","SUSPECTED");
            case "RATE_LIMIT_COOLDOWN"->new Classification("rate_limit","unconfirmed","SUSPECTED");
            case "API_QUOTA_EXHAUSTED"->new Classification("quota_exhausted","unconfirmed","SUSPECTED");
            case "AUTH_MISSING"->new Classification("authentication","unconfirmed","CONFIRMED");
            case "MODEL_MISSING","RESPONSE_MODEL_UNVERIFIED"->new Classification("model_unavailable","unconfirmed","CONFIRMED");
            case "GPU_DEVICE_LOST","VRAM_OOM"->new Classification("gpu_unavailable","unconfirmed","CONFIRMED");
            case "HEALTH_DOWN"->new Classification("local_model_unavailable","unconfirmed","CONFIRMED");
            case "LOCAL_PROVIDER_UNAVAILABLE"->new Classification("local_model_unavailable","unconfirmed","CONFIRMED");
            case "PROCESS_EXIT"->new Classification("process_exit","unconfirmed","CONFIRMED");
            case "SOFT_CIRCUIT_OPEN"->new Classification("provider_error","unconfirmed","SUSPECTED");
            case "PROVIDER_ERROR","BAD_REQUEST","CONTEXT_TOO_SMALL","EMBEDDING_DIM_MISMATCH",
                    "LOCAL_UNSUPPORTED_MANAGED_RAG"->new Classification("provider_error","unconfirmed","SUSPECTED");
            case "STREAM_ERROR"->new Classification("network","unconfirmed","SUSPECTED");
            default->new Classification("unconfirmed","unconfirmed","UNKNOWN");
        };
        if(Set.of("NONE","CANCELLED_NEUTRAL","DISABLED").contains(failureClass))return null;
        Incident row=recordClassified(provider,model,0,classified);
        ApiRoutingDebug.failure("llm",row.provider(),row.model(),null,1,null,
                failureClass==null?null:failureClass.trim().toLowerCase(Locale.ROOT),null);
        return row;
    }
    /** Search receipts: request/auth/quota/timeout/provider failures are incidents; honest zero results are not. */
    public synchronized Incident recordSearch(String provider,String model,String failureReason) {
        Classification classified=switch(Objects.toString(failureReason,"")) {
            case "AUTH_OR_CONFIG"->new Classification("authentication","unconfirmed","CONFIRMED");
            case "RATE_LIMIT"->new Classification("rate_limit","unconfirmed","CONFIRMED");
            case "TIMEOUT_OR_BUDGET"->new Classification("timeout","unconfirmed","SUSPECTED");
            case "PROVIDER_ERROR"->new Classification("provider_error","unconfirmed","CONFIRMED");
            case "BREAKER_OR_COOLDOWN","PARSE_ERROR"->new Classification("provider_error","unconfirmed","SUSPECTED");
            default->null;
        };
        return classified==null?null:recordClassified(provider,model,0,classified);
    }
    /** A later success for provider+model: closes the streak, stamps recovery, keeps prior incidents intact. */
    public synchronized void recordSuccess(String provider,String model) {
        String api=provider!=null&&PROVIDERS.contains(provider)?provider:"unknown";
        String safeModel=safeModel(model);
        String stateKey=api+"|"+safeModel;
        var state=states.computeIfAbsent(stateKey,k->new ProviderState());
        String now=clock.instant().toString();
        boolean recovered=state.consecutive>0;
        if(recovered)state.recoveredAt=now;
        state.consecutive=0;state.lastSuccessAt=now;
        if(!recovered)return;
        String prefix=stateKey+"|";
        boolean changed=false;
        for(var entry:incidents.entrySet())if(entry.getKey().startsWith(prefix)){
            var row=entry.getValue();
            entry.setValue(new Incident(row.provider(),row.model(),row.httpStatus(),row.category(),row.errorCode(),
                    row.firstSeen(),row.lastSeen(),row.count(),row.scope(),row.evidence(),0,
                    state.lastSuccessAt,state.recoveredAt,row.maskedBy(),row.maskedAt()));
            changed=true;
        }
        if(changed)schedulePersistence();
    }
    /** Fallback success does not erase the failure; it annotates which route covered for the user. */
    public synchronized void markMasked(String provider,String model,String fallbackProvider,String fallbackModel) {
        if(provider==null||fallbackProvider==null)return;
        String label=(PROVIDERS.contains(fallbackProvider)?fallbackProvider:"unknown")+"/"+safeModel(fallbackModel);
        String prefix=provider+"|"+safeModel(model)+"|";
        String now=clock.instant().toString();
        boolean changed=false;
        for(var entry:incidents.entrySet())if(entry.getKey().startsWith(prefix)){
            var row=entry.getValue();
            if(label.equals(row.maskedBy()))continue;
            entry.setValue(new Incident(row.provider(),row.model(),row.httpStatus(),row.category(),row.errorCode(),
                    row.firstSeen(),row.lastSeen(),row.count(),row.scope(),row.evidence(),row.consecutive(),
                    row.lastSuccessAt(),row.recoveredAt(),label,now));
            ApiRoutingDebug.failure("llm",row.provider(),row.model(),null,1,null,row.category(),label);
            changed=true;
        }
        if(changed)schedulePersistence();
    }
    /** Current per provider+model state for the diagnostics status view; derived only from recorded rows. */
    public synchronized Map<String,Object> statusSummary() {
        var groups=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:incidents.values()){
            String key=row.provider()+"|"+row.model();
            var agg=groups.computeIfAbsent(key,k->new LinkedHashMap<String,Object>());
            agg.put("provider",row.provider());agg.put("model",row.model());agg.put("scope",row.scope());
            agg.merge("totalCount",row.count(),(a,b)->(long)a+(long)b);
            Object last=agg.get("lastSeen");
            if(last==null||row.lastSeen().compareTo(String.valueOf(last))>0){
                agg.put("lastSeen",row.lastSeen());agg.put("category",row.category());agg.put("evidence",row.evidence());
            }
            if(row.maskedBy()!=null&&(agg.get("maskedAt")==null||row.maskedAt().compareTo(String.valueOf(agg.get("maskedAt")))>0)){
                agg.put("maskedBy",row.maskedBy());agg.put("maskedAt",row.maskedAt());
            }
        }
        var providers=new ArrayList<Map<String,Object>>();
        long recentMs=600_000L,nowMs=clock.millis();
        String overall="OK";
        var merged=new LinkedHashMap<String,Map<String,Object>>(groups);
        for(var entry:states.entrySet())merged.computeIfAbsent(entry.getKey(),k->{
            var row=new LinkedHashMap<String,Object>();String[] parts=k.split("\\|",2);
            row.put("provider",parts[0]);row.put("model",parts.length>1?parts[1]:"unconfirmed");
            row.put("scope",scopeOf(parts[0]));row.put("totalCount",0L);return row;
        });
        for(var entry:merged.entrySet()){
            var agg=entry.getValue();var state=states.get(entry.getKey());
            long consecutive=state==null?0:state.consecutive;
            agg.put("consecutive",consecutive);
            if(state!=null&&state.lastSuccessAt!=null)agg.put("lastSuccessAt",state.lastSuccessAt);
            if(state!=null&&state.recoveredAt!=null)agg.put("recoveredAt",state.recoveredAt);
            boolean recent=false;
            try{recent=agg.get("lastSeen")!=null&&nowMs-Instant.parse(String.valueOf(agg.get("lastSeen"))).toEpochMilli()<recentMs;}
            catch(Exception ignored){}
            String state2=!recent||consecutive==0?"OK":consecutive>=2?"DEGRADED":"WARNING";
            agg.put("state",state2);
            if("DEGRADED".equals(state2))overall="DEGRADED";else if("WARNING".equals(state2)&&!"DEGRADED".equals(overall))overall="WARNING";
            providers.add(agg);
        }
        providers.sort(Comparator.comparingInt((Map<String,Object> m)->switch(String.valueOf(m.get("state"))){
                    case "DEGRADED"->0;case "WARNING"->1;default->2;})
                .thenComparing(m->String.valueOf(m.getOrDefault("lastSeen","")),Comparator.reverseOrder()));
        return Map.of("overall",overall,"providers",providers);
    }
    private synchronized Incident recordClassified(String provider,String model,int status,Classification classification) {
        String api=provider!=null&&PROVIDERS.contains(provider)?provider:"unknown";
        String safeModel=safeModel(model);
        Integer http=status>=100&&status<=599?status:null;
        String key=key(api,safeModel,http,classification.category(),classification.errorCode());
        var before=incidents.get(key);String now=clock.instant().toString();
        var state=states.computeIfAbsent(api+"|"+safeModel,k->new ProviderState());
        state.consecutive++;
        var row=new Incident(api,safeModel,http,classification.category(),classification.errorCode(),
                before==null?now:before.firstSeen(),now,before==null?1:before.count()+1,
                scopeOf(api),classification.evidence(),state.consecutive,
                state.lastSuccessAt,state.recoveredAt,before==null?null:before.maskedBy(),before==null?null:before.maskedAt());
        incidents.put(key,row);
        if(incidents.size()>512)incidents.remove(incidents.keySet().iterator().next());
        schedulePersistence();
        // Persist the latest aggregate on a bounded writer; console/UI event floods remain bounded.
        if(before==null||(row.count()&(row.count()-1))==0) {
            LOG.warn("[API_FAILURE] api={} model={} status={} reason={} first={} latest={} count={}",
                    api,safeModel,http,row.category(),row.firstSeen(),row.lastSeen(),row.count());
            if(events!=null)try {
                var data=new LinkedHashMap<String,Object>();
                data.put("provider",api);data.put("model",safeModel);data.put("httpStatus",http);
                data.put("failureClass",row.category());data.put("errorCode",row.errorCode());
                data.put("scope",row.scope());data.put("evidence",row.evidence());data.put("consecutive",row.consecutive());
                data.put("firstSeen",row.firstSeen());data.put("lastSeen",row.lastSeen());data.put("count",row.count());
                events.emit(DebugProbeType.HTTP,DebugEventLevel.WARN,key,"API failure before fallback","api.failure",data,null);
            }catch(RuntimeException ignored){LOG.warn("[API_FAILURE] event_store_unavailable");}
        }
        return row;
    }

    public Incident recordHttp(String url,String model,int status,String body,Throwable failure) {
        return record(provider(url),model,status,body,failure);
    }
    /** Refine this HTTP observation without counting it twice or overwriting a newer failure. */
    public synchronized void refineHttp(Incident observation,String body) {
        if(observation==null||observation.httpStatus()==null)return;
        String key=key(observation.provider(),observation.model(),observation.httpStatus(),observation.category(),observation.errorCode());
        var current=incidents.get(key);
        if(current==null||current.count()!=observation.count()||!current.lastSeen().equals(observation.lastSeen()))return;
        var classification=classify(current.httpStatus(),body,null);
        if(classification.errorCode().equals("unconfirmed"))return;
        incidents.put(key,new Incident(current.provider(),current.model(),current.httpStatus(),classification.category(),classification.errorCode(),
                current.firstSeen(),current.lastSeen(),current.count(),current.scope(),classification.evidence(),current.consecutive(),
                current.lastSuccessAt(),current.recoveredAt(),current.maskedBy(),current.maskedAt()));
        schedulePersistence();
        LOG.warn("[API_FAILURE_DETAIL] api={} model={} status={} reason={} code={} count={}",current.provider(),current.model(),current.httpStatus(),classification.category(),classification.errorCode(),current.count());
    }
    public void recordException(String url,String model,Throwable failure) {
        int status=0;String body=null;
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable e=failure;e!=null&&seen.add(e);e=e.getCause()) {
            if(e instanceof dev.langchain4j.exception.HttpException http){status=http.statusCode();body=http.getMessage();break;}
            if(e instanceof org.springframework.web.reactive.function.client.WebClientResponseException http){status=http.getStatusCode().value();body=http.getResponseBodyAsString();break;}
            if(e instanceof org.springframework.web.client.HttpStatusCodeException http){status=http.getStatusCode().value();body=http.getResponseBodyAsString();break;}
            if(e==e.getCause())break;
        }
        recordHttp(url,model,status,body,failure);
    }
    private static boolean cancelled(Throwable failure) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(var e=failure;e!=null&&seen.add(e);e=e.getCause())
            if(e instanceof java.util.concurrent.CancellationException||e instanceof InterruptedException)return true;
        return false;
    }

    public static Classification classify(int status,String body,Throwable error) {
        String code="unconfirmed";
        if(body!=null&&body.length()<=16_384)try {
            var json=JSON.readTree(body);
            for(var node:List.of(json.path("error").path("code"),json.path("error").path("type"),json.path("error").path("status"),json.path("errorCode"),json.path("code"))) {
                if(CODES.contains(node.asText())){code=node.asText();break;}
            }
        }catch(Exception ignored){/* Never retain or print the body. */}
        String category;
        if(Set.of("insufficient_quota","credit_balance_too_low","insufficient_balance","billing_hard_limit_reached","organization_usage_limit_exceeded").contains(code))category="billing_quota";
        else if(Set.of("QUOTA_LIMITED","SUBSCRIPTION_EXHAUSTED").contains(code))category="quota_exhausted";
        else if(Set.of("rate_limit_exceeded","rate_limit_error","RATE_LIMITED").contains(code))category="rate_limit";
        else if(Set.of("invalid_api_key","authentication_error","UNAUTHENTICATED","SUBSCRIPTION_TOKEN_INVALID","024","028").contains(code))category="authentication";
        else if(Set.of("model_not_found","model_not_allowed","model_permission_denied").contains(code))category="model";
        else if(Set.of("permission_error","PERMISSION_DENIED","SUBSCRIPTION_NOT_FOUND","012","010").contains(code))category="permission_or_policy";
        else if(code.equals("unsupported_country_region_territory"))category="region_policy";
        else category=switch(status){case 401->"authentication";case 402->"payment_required";case 403->"permission_or_policy";case 404->"model_or_endpoint";case 408,504->"timeout";case 429->"quota_or_rate_limit";default->status>=500?"provider_server_error":status>=400?"request_error":transportCategory(error);};
        // Explicit provider codes and HTTP statuses are observed facts; transport inference is only suspected.
        String evidence=!code.equals("unconfirmed")||status>=400?"CONFIRMED":"unconfirmed".equals(category)?"UNKNOWN":"SUSPECTED";
        return new Classification(category,code,evidence);
    }
    private static String transportCategory(Throwable error) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(var e=error;e!=null&&seen.add(e);e=e.getCause()) {
            if(e instanceof java.net.SocketTimeoutException||e instanceof java.net.http.HttpTimeoutException||e instanceof java.util.concurrent.TimeoutException)return "timeout";
            if(e instanceof java.net.UnknownHostException)return "address_dns";
            if(e instanceof javax.net.ssl.SSLException)return "tls";
            if(e instanceof java.net.ConnectException)return "connection";
        }
        return "unconfirmed";
    }
    public static String provider(String url) {
        try {
            String host=URI.create(url).getHost();if(host==null)return "unknown";
            return switch(host.toLowerCase(Locale.ROOT)) {
                case "api.openai.com"->"openai";case "generativelanguage.googleapis.com"->"gemini";
                case "api.groq.com"->"groq";case "api.search.brave.com"->"brave";case "openapi.naver.com"->"naver";
                case "serpapi.com"->"serpapi";case "api.tavily.com"->"tavily";case "api.deepgram.com"->"deepgram";
                case "api.soniox.com","stt-rt.soniox.com"->"soniox";case "dapi.kakao.com"->"kakao";
                case "api.anthropic.com"->"anthropic";case "api.z.ai"->"zai";case "ai-gateway.vercel.sh"->"vercel";
                case "opencode.ai"->"opencode";case "localhost","127.0.0.1","::1"->"local";
                default->host.endsWith(".pinecone.io")?"pinecone":host.endsWith(".upstash.io")?"upstash":host.endsWith(".supabase.co")?"supabase":"unknown";
            };
        }catch(Exception e){return "unknown";}
    }
    public static String requestModel(String body) {
        if(body==null||body.length()>2_000_000)return "unconfirmed";
        try{return safeModel(JSON.readTree(body).path("model").asText());}catch(Exception e){return "unconfirmed";}
    }
    private static String safeModel(String model) {
        return model!=null&&model.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,95}")
                &&!model.matches("(?i).*(sk-|gsk_|AIza|token|secret|password).*")?model:"unconfirmed";
    }
    // HTTP failures group by API/model/status; category/code describe the latest observed response.
    private static String key(String api,String model,Integer status,String category,String code){return api+"|"+model+"|"+(status==null?category:status);}
    private void load() {
        try {
            if(!Files.exists(path))return;
            if(Files.isSymbolicLink(path)||Files.size(path)>1_048_576)throw new java.io.IOException();
            for(var row:JSON.readValue(Files.readString(path),Incident[].class)) {
                if(!PROVIDERS.contains(row.provider())||!safeModel(row.model()).equals(row.model())||row.count()<1
                        ||row.category()==null||!row.category().matches("[a-z_]{1,40}")
                        ||!(row.errorCode().equals("unconfirmed")||CODES.contains(row.errorCode())))continue;
                Instant.parse(row.firstSeen());Instant.parse(row.lastSeen());
                if(row.lastSuccessAt()!=null)Instant.parse(row.lastSuccessAt());
                if(row.recoveredAt()!=null)Instant.parse(row.recoveredAt());
                if(row.maskedAt()!=null)Instant.parse(row.maskedAt());
                // Legacy rows lack the newer fields; unsafe values are dropped rather than trusted.
                String scope=row.scope()!=null&&SCOPES.contains(row.scope())?row.scope():scopeOf(row.provider());
                String evidence=row.evidence()!=null&&EVIDENCE.contains(row.evidence())?row.evidence():"UNKNOWN";
                String maskedBy=row.maskedBy()!=null&&row.maskedBy().matches("[a-zA-Z0-9._:/|-]{1,120}")?row.maskedBy():null;
                var restored=new Incident(row.provider(),row.model(),row.httpStatus(),row.category(),row.errorCode(),
                        row.firstSeen(),row.lastSeen(),row.count(),scope,evidence,Math.max(0,row.consecutive()),
                        row.lastSuccessAt(),row.recoveredAt(),maskedBy,row.maskedAt());
                if(incidents.size()<512)incidents.put(key(row.provider(),row.model(),row.httpStatus(),row.category(),row.errorCode()),restored);
                var state=states.computeIfAbsent(row.provider()+"|"+row.model(),k->new ProviderState());
                state.consecutive=Math.max(state.consecutive,restored.consecutive());
                if(restored.lastSuccessAt()!=null)state.lastSuccessAt=restored.lastSuccessAt();
                if(restored.recoveredAt()!=null)state.recoveredAt=restored.recoveredAt();
            }
        }catch(Exception e){persistenceHealthy=false;LOG.warn("[API_FAILURE] persistence_read_failed");}
    }
    private void schedulePersistence() {
        var rows=List.copyOf(incidents.values());
        if(!writer.isShutdown())writer.execute(()->persist(rows));
        else persistenceHealthy=false;
    }
    boolean awaitPersistence() throws InterruptedException {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while((writer.getActiveCount()>0||!writer.getQueue().isEmpty())&&System.nanoTime()<until)Thread.sleep(5);
        return writer.getActiveCount()==0&&writer.getQueue().isEmpty();
    }
    @jakarta.annotation.PreDestroy
    public void close() {
        writer.shutdown();
        try {if(!writer.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS))persistenceHealthy=false;}
        catch(InterruptedException e){Thread.currentThread().interrupt();persistenceHealthy=false;}
    }
    private void persist(List<Incident> rows) {
        Path temp=null;
        try {
            Files.createDirectories(path.getParent());
            if(Files.isSymbolicLink(path))throw new java.io.IOException();
            temp=Files.createTempFile(path.getParent(),"api-failures-",".tmp");
            Files.writeString(temp,JSON.writeValueAsString(rows));
            try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
            persistenceHealthy=true;
        }catch(Exception e){persistenceHealthy=false;LOG.warn("[API_FAILURE] persistence_write_failed");}
        finally {if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
}
