package com.example.lms.routing;

import com.example.lms.llm.NamedChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Immutable role/candidate identity with one run-owned, thread-safe fallback budget. */
public final class RoutingInvocation {
    public record Observation(RoutingProfile.Role role,int attemptCount,int extraFallbackCallCount,
            String responseModelId,String selectedTarget,String reasonCode,boolean succeeded) {}
    private final RunRoutingSnapshot.ResolvedBinding binding;
    private final AtomicInteger extras=new AtomicInteger();
    private final AtomicInteger attempts=new AtomicInteger();
    private volatile Observation latest;
    public RoutingInvocation(RunRoutingSnapshot.ResolvedBinding binding){this.binding=Objects.requireNonNull(binding);}
    public RunRoutingSnapshot.ResolvedBinding binding(){return binding;}
    public List<RunRoutingSnapshot.Candidate> fallbackCandidates(){return binding.orderedFallbacks();}
    public boolean extraAvailable(){return extras.get()<binding.maxExtraFallbackCalls();}
    public Optional<RunRoutingSnapshot.Candidate> candidate(String target){return binding.candidates().stream().filter(c->c.target().equals(target)).findFirst();}
    public static Optional<RoutingInvocation> current(RoutingProfile.Role role){
        var run=com.example.lms.service.chat.ChatRunExecutionContext.current();
        return run==null?Optional.empty():run.routingInvocation(role);
    }
    public boolean matches(String target,String model,String provider){
        return candidate(target).filter(c->c.modelId().equals(model) && normalize(c.provider()).equals(normalize(provider))).isPresent();
    }
    public boolean matchesEndpoint(String target,String endpointHash){
        return candidate(target).filter(c->c.endpointIdentityHash()!=null && c.endpointIdentityHash().equals(endpointHash)).isPresent();
    }
    private static String normalize(String provider){String p=provider==null?"":provider.toLowerCase(Locale.ROOT);return Set.of("local","ollama","ollama_chat","ollama_native","local_openai_compatible").contains(p)?"local":p;}
    public ChatModel wrap(ChatModel delegate,RunRoutingSnapshot.Candidate selected,boolean fallback){
        if(!binding.candidates().contains(selected))throw new IllegalArgumentException("routing_candidate_forbidden");
        Objects.requireNonNull(delegate);
        return new NamedChatModel(){
            @Override public String resolvedModelName(){return selected.modelId();}
            @Override public ChatResponse doChat(ChatRequest request){
                com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
                if(fallback){
                    int used;
                    do{used=extras.get();if(used>=binding.maxExtraFallbackCalls())throw new IllegalStateException("routing_fallback_budget_exhausted");}
                    while(!extras.compareAndSet(used,used+1));
                }
                attempts.incrementAndGet();
                try{
                    ChatResponse response=delegate.chat(request);
                    String actual=response==null||response.metadata()==null?null:response.metadata().modelName();
                    if(!RoutingProfile.identifier(actual))actual=null;
                    latest=new Observation(binding.role(),attempts.get(),extras.get(),actual,selected.target(),null,true);
                    return response;
                }catch(RuntimeException failure){
                    latest=new Observation(binding.role(),attempts.get(),extras.get(),null,selected.target(),"generation_failed",false);
                    throw failure;
                }
            }
        };
    }
    public Observation observation(){var value=latest;return value==null?new Observation(binding.role(),attempts.get(),extras.get(),null,null,"not_observed",false):value;}
}
