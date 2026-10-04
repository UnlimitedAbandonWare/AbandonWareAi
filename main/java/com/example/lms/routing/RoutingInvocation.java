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
            String responseModelId,String selectedTarget,String reasonCode,boolean succeeded,
            String requestedModelId,String selectedModelId,java.math.BigDecimal reservedUsd) {
        public Observation(RoutingProfile.Role role,int attempts,int extras,String actual,String target,String reason,boolean success){
            this(role,attempts,extras,actual,target,reason,success,null,null,java.math.BigDecimal.ZERO);
        }
    }
    private final RunRoutingSnapshot.ResolvedBinding binding;
    private final AtomicInteger extras=new AtomicInteger();
    private final AtomicInteger attempts=new AtomicInteger();
    private volatile Observation latest;
    private java.math.BigDecimal reservedUsd=java.math.BigDecimal.ZERO;
    private volatile String terminalReason;
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
        var parameters=Objects.requireNonNull(delegate).defaultRequestParameters();
        return wrap(delegate,selected,fallback,parameters==null?null:parameters.maxOutputTokens());
    }
    public ChatModel wrap(ChatModel delegate,RunRoutingSnapshot.Candidate selected,boolean fallback,Integer maxOutput){
        if(!binding.candidates().contains(selected))throw new IllegalArgumentException("routing_candidate_forbidden");
        Objects.requireNonNull(delegate);
        return new NamedChatModel(){
            @Override public String resolvedModelName(){return selected.modelId();}
            @Override public dev.langchain4j.model.chat.request.ChatRequestParameters defaultRequestParameters(){
                return delegate.defaultRequestParameters();
            }
            @Override public ChatResponse doChat(ChatRequest request){
                try{
                    com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
                    Runnable admission=()->admit(selected,fallback,maxOutput,request);
                    var run=com.example.lms.service.chat.ChatRunExecutionContext.current();
                    if(run==null)admission.run();
                    else if(!run.admitCall(admission))throw new java.util.concurrent.CancellationException();
                    ChatRequest wireRequest=request;
                    if(binding.role().auxiliary() && selected.paid()){
                        var bounded=request.parameters() instanceof dev.langchain4j.model.openai.OpenAiChatRequestParameters p
                                && p.maxCompletionTokens()!=null
                                ? dev.langchain4j.model.openai.OpenAiChatRequestParameters.builder().maxCompletionTokens(maxOutput).build()
                                : dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder().maxOutputTokens(maxOutput).build();
                        wireRequest=ChatRequest.builder().messages(request.messages())
                                .parameters(request.parameters().overrideWith(bounded)).build();
                    }
                    ChatResponse response=delegate.chat(wireRequest);
                    String actual=response==null||response.metadata()==null?null:response.metadata().modelName();
                    if(!RoutingProfile.identifier(actual))actual=null;
                    observe(selected,actual,null,true);
                    return response;
                }catch(RuntimeException failure){
                    String reason=terminalReason;
                    if(com.example.lms.llm.gateway.LlmGatewayFailureClassifier.isCancellation(failure))reason="cancelled";
                    else if(binding.role().auxiliary() && nonReplayable(failure))reason="permission_denied";
                    if(reason!=null)terminalReason=reason;
                    observe(selected,null,reason==null?"generation_failed":reason,false);
                    throw failure;
                }
            }
        };
    }
    private static boolean nonReplayable(Throwable failure){
        if(com.example.lms.llm.gateway.LlmGatewayFailureClassifier.hasNonReplayableReason(failure))return true;
        int depth=0;
        for(Throwable cause=failure;cause!=null && depth++<32;cause=cause.getCause()){
            if(cause instanceof org.springframework.web.reactive.function.client.WebClientResponseException http
                    && (http.getStatusCode().value()==401 || http.getStatusCode().value()==403))return true;
            if(cause instanceof dev.langchain4j.exception.HttpException http && (http.statusCode()==401 || http.statusCode()==403))return true;
            if(cause instanceof com.example.lms.llm.gateway.LlmGatewayException gateway
                    && (gateway.failureClass().name().contains("AUTH") || gateway.failureClass().name().contains("PERMISSION")))return true;
            if(cause.getCause()==cause)break;
        }
        return false;
    }
    private void deny(String reason){terminalReason=reason;throw new IllegalStateException(reason);}
    private synchronized void admit(RunRoutingSnapshot.Candidate selected,boolean fallback,Integer maxOutput,ChatRequest request){
        if(terminalReason!=null)deny(terminalReason);
        if(fallback && extras.get()>=binding.maxExtraFallbackCalls())deny("routing_fallback_budget_exhausted");
        if(binding.role().auxiliary() && selected.paid() && !binding.auxPaidEnabled())deny("auxiliary_paid_disabled");
        if(binding.role().auxiliary() && request.parameters().modelName()!=null
                && !selected.modelId().equals(request.parameters().modelName()))
            deny("routing_candidate_identity_changed");
        if(!selected.supports(binding.role()))deny("route_capability_unverified");
        java.math.BigDecimal cost=java.math.BigDecimal.ZERO;
        if(binding.role().auxiliary() && selected.paid()){
            if(!binding.auxPaidEnabled())deny("auxiliary_paid_disabled");
            var price=selected.price();
            if(price==null || !price.fresh())deny("auxiliary_price_unverified");
            var primary=binding.primary().price();
            if(fallback && (primary==null || price.input().compareTo(primary.input())>0
                    || price.output().compareTo(primary.output())>0))deny("auxiliary_cost_escalation_forbidden");
            if(maxOutput==null || maxOutput<=0)deny("auxiliary_output_bound_unverified");
            Integer requested=request.parameters().maxOutputTokens();
            if(requested!=null && requested>maxOutput)deny("auxiliary_output_bound_exceeded");
            if(request.parameters() instanceof dev.langchain4j.model.openai.OpenAiChatRequestParameters openAi
                    && openAi.maxCompletionTokens()!=null && openAi.maxCompletionTokens()>maxOutput)deny("auxiliary_output_bound_exceeded");
            // This bounded phase admits text only. Unknown tool/image/schema overhead cannot be priced as zero.
            if(request.parameters().toolSpecifications()!=null && !request.parameters().toolSpecifications().isEmpty()
                    || request.parameters().responseFormat()!=null)deny("auxiliary_content_unverified");
            for(var message:request.messages()){
                if(message instanceof dev.langchain4j.data.message.UserMessage user
                        && user.contents().stream().anyMatch(c->!(c instanceof dev.langchain4j.data.message.TextContent)))
                    deny("auxiliary_content_unverified");
                if(!(message instanceof dev.langchain4j.data.message.UserMessage)
                        && !(message instanceof dev.langchain4j.data.message.SystemMessage))deny("auxiliary_content_unverified");
            }
            long inputBytes=request.messages().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+4096L;
            cost=price.input().multiply(java.math.BigDecimal.valueOf(inputBytes))
                    .add(price.output().multiply(java.math.BigDecimal.valueOf(maxOutput)))
                    .movePointLeft(6);
            if(binding.auxCostCapUsdPerRun()==null || binding.auxCostCapUsdPerRun().signum()<=0
                    || binding.auxCostCapUsdPerRun().compareTo(new java.math.BigDecimal("0.05"))>0
                    || reservedUsd.add(cost).compareTo(binding.auxCostCapUsdPerRun())>0)
                deny("auxiliary_cost_cap_exceeded");
        }
        reservedUsd=reservedUsd.add(cost);
        if(fallback)extras.incrementAndGet();
        attempts.incrementAndGet();
    }
    private synchronized void observe(RunRoutingSnapshot.Candidate selected,String actual,String reason,boolean success){
        latest=new Observation(binding.role(),attempts.get(),extras.get(),actual,selected.target(),reason,success,
                binding.primary().modelId(),selected.modelId(),reservedUsd);
    }
    public synchronized java.math.BigDecimal reservedUsd(){return reservedUsd;}
    public Observation observation(){var value=latest;return value==null?new Observation(binding.role(),attempts.get(),extras.get(),null,null,"not_observed",false,binding.primary().modelId(),null,reservedUsd()):value;}
}
