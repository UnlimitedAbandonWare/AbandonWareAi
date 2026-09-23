package com.example.lms.assist;

import com.abandonware.ai.addons.budget.*;
import com.example.lms.llm.OllamaNativeChatModel;
import com.example.lms.llm.gateway.*;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Only the existing bounded assist worker executes this; API failover shares the existing router and verifier. */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class ConversateLocalCardGenerator {
    @FunctionalInterface interface Call {String invoke(List<ChatMessage> messages,Map<String,Object> schema);}
    public record Result(String reason,ConversateSessionService.Card card,int attempts,int complexJudgments,long elapsedMs,Long verificationMs) {
        public Result(String reason,ConversateSessionService.Card card,int attempts,int complexJudgments,long elapsedMs){this(reason,card,attempts,complexJudgments,elapsedMs,null);}
        @Override public String toString(){return "Generation[reason="+reason+",attempts="+attempts+"]";}
    }
    private final Call call;private final Call supportCall;private final int timeoutMs;private final String disabledReason;
    @Autowired(required=false) private ai.abandonware.nova.orch.aop.LlmRouterAspect gateway;
    @Autowired
    public ConversateLocalCardGenerator(@Value("${conversate.generation.enabled:false}") boolean enabled,
            @Value("${conversate.generation.base-url:}") String baseUrl,@Value("${conversate.generation.model:}") String modelName,
            @Value("${conversate.generation.timeout-ms:4000}") int timeoutMs){
        this.timeoutMs=Math.max(100,Math.min(4000,timeoutMs));
        disabledReason=!enabled?"GENERATION_DISABLED":!loopback(baseUrl)?"LOCAL_ROUTE_REJECTED":modelName==null||modelName.isBlank()?"MODEL_NOT_CONFIGURED":null;
        if(disabledReason!=null){call=null;supportCall=null;}
        else {var model=new OllamaNativeChatModel(baseUrl,modelName,Duration.ofMillis(this.timeoutMs),512,0.0,null,null,false);
            supportCall=(messages,schema)->model.chatStructured(messages,schema).aiMessage().text();
            call=(messages,schema)->{
                dev.langchain4j.model.chat.ChatModel nativeCall=new dev.langchain4j.model.chat.ChatModel(){
                    @Override public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request){return model.chatStructured(request.messages(),schema);}
                };
                return (gateway==null?nativeCall:gateway.routeLocalInference(nativeCall,baseUrl,modelName,this.timeoutMs))
                        .chat(messages).aiMessage().text();
            };}
    }
    ConversateLocalCardGenerator(Call call,int timeoutMs){this.call=Objects.requireNonNull(call);this.supportCall=call;this.timeoutMs=Math.max(100,Math.min(4000,timeoutMs));disabledReason=null;}
    public Result generate(String question,List<ConversateCardPrompt.Evidence> evidence,boolean complex,long now){
        return execute(()->ConversateCardPrompt.build(question,evidence,complex),raw->ConversateCardVerifier.verify(raw,evidence,complex,now),complex,now);
    }
    public Result suggest(String question,List<String> context,long now){
        return execute(()->ConversateCardPrompt.suggestion(question,context),raw->ConversateCardVerifier.verifySuggestion(raw,now),false,now);
    }
    /** Last-resort support chooses a verified fixed suggestion; it cannot restart the exhausted API ladder. */
    public Result suggestSupport(String question,List<String> context,long now){
        return execute(()->ConversateCardPrompt.suggestion(question,context),raw->ConversateCardVerifier.verifySuggestion(raw,now),false,now,supportCall,false);
    }
    private Result execute(java.util.function.Supplier<ConversateCardPrompt.Request> requestFactory,java.util.function.Function<String,ConversateCardVerifier.Verified> verifier,boolean complex,long now){
        return execute(requestFactory,verifier,complex,now,call,true);
    }
    private Result execute(java.util.function.Supplier<ConversateCardPrompt.Request> requestFactory,java.util.function.Function<String,ConversateCardVerifier.Verified> verifier,boolean complex,long now,Call selected,boolean routed){
        long began=System.nanoTime();int attempts=0;var prior=TimeBudgetContext.get();
        try {
            if(Thread.currentThread().isInterrupted())return result("CANCELLED",now,0,0,began);
            if(disabledReason!=null)return result(disabledReason,now,0,0,began);
            long remaining=prior==null?timeoutMs:prior.capWaitMillis(timeoutMs);if(remaining<=0)return result("GENERATION_TIMEOUT",now,0,0,began);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remaining);
            var request=requestFactory.get();
            TimeBudgetContext.set(TimeBudget.untilNanoDeadline(deadline));attempts=1;
            if(routed&&gateway!=null)com.example.lms.search.TraceStore.put("llm.gateway.fallback.count",0);
            String raw=selected.invoke(request.messages(),request.schema());
            if(routed)attempts=attemptCount(attempts);
            if(Thread.currentThread().isInterrupted())return result("CANCELLED",now,attempts,complex?1:0,began);
            if(TimeBudgetContext.get().expired())return result("GENERATION_TIMEOUT",now,attempts,complex?1:0,began);
            long verificationBegan=System.nanoTime();var verified=verifier.apply(raw);
            return new Result(verified.reason(),verified.card(),attempts,complex?1:0,elapsed(began),elapsed(verificationBegan));
        }catch(RuntimeException failure){
            return result(classify(failure),now,routed?attemptCount(attempts):attempts,attempts>0&&complex?1:0,began);
        }finally{if(prior==null)TimeBudgetContext.clear();else TimeBudgetContext.set(prior);}
    }
    private int attemptCount(int started){Object count=com.example.lms.search.TraceStore.get("llm.gateway.fallback.count");
        return gateway!=null&&started>0&&count instanceof Number n?1+Math.max(0,Math.min(2,n.intValue())):started;}
    static String classify(RuntimeException failure){
        if(Thread.currentThread().isInterrupted()||LlmGatewayFailureClassifier.isCancellation(failure))return "CANCELLED";
        int depth=0;
        for(Throwable cause=failure;cause!=null&&depth++<20;cause=cause.getCause()){
            if(cause instanceof LlmGatewayException gateway&&"blank_response".equals(gateway.reasonCode()))return "GENERATION_EMPTY";
        }
        return switch(new LlmGatewayFailureClassifier().classify(failure)){
            case CANCELLED_NEUTRAL->"CANCELLED";
            case AUTH_MISSING->"GENERATION_DENIED";
            case MODEL_MISSING->"MODEL_NOT_FOUND";
            case RATE_LIMIT_COOLDOWN->"GENERATION_RATE_LIMITED";
            case TIMEOUT_SOFT->"GENERATION_TIMEOUT";
            default->failure instanceof IllegalArgumentException?"GENERATION_INPUT_REJECTED":"GENERATION_UNAVAILABLE";
        };
    }
    private static boolean loopback(String base){try{var uri=URI.create(base);return Set.of("http","https").contains(uri.getScheme())&&Set.of("127.0.0.1","[::1]","::1").contains(uri.getHost())&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null&&Set.of("","/","/v1","/v1/").contains(uri.getPath());}catch(Exception invalid){return false;}}
    private static long elapsed(long began){return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);}
    private static Result result(String reason,long now,int attempts,int complex,long began){var rejected=ConversateCardVerifier.rejected(reason,now);return new Result(reason,rejected.card(),attempts,complex,elapsed(began));}
}
