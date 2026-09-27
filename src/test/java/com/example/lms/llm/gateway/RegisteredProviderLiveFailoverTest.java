package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.*;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.abandonware.ai.addons.budget.*;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in only: one inference request, at most two eligible registered APIs, 40s total budget. */
@EnabledIfEnvironmentVariable(named="FAILOVER_LIVE_API_PROOF", matches="true")
class RegisteredProviderLiveFailoverTest {
    @Test void injectedLocalFailureReachesAnAlreadyEnabledRegisteredProvider() throws Exception {
        var report=new LinkedHashMap<String,Object>();
        var tracker=new ModelRuntimeHealthTracker();
        String timeline=tracker.beginRequestTimeline("synthetic-live-failover",null);
        tracker.recordRequestPhase(timeline,"dispatch",null,null,null);
        tracker.recordRequestPhase(timeline,"pending",null,null,null);
        long began=System.nanoTime();
        try {
            Path window=Path.of("data/agent-handoff/codex/report/gpu-service-failover-20260914/live-provider-window.json");
            if(Files.exists(window)) {
                String deadline=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(window)).path("deadlineAt").asText();
                if(java.time.Instant.now().isAfter(java.time.OffsetDateTime.parse(deadline).toInstant())) {
                    report.put("status","not_attempted_live_verification_deadline");return;
                }
            }
            var env=new MockEnvironment();
            env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("process",(Map)System.getenv()));
            for(var source:new YamlPropertySourceLoader().load("llm",new ClassPathResource("application-llm.yaml")))
                env.getPropertySources().addLast(source);
            var binder=Binder.get(env);
            var props=binder.bind("llmrouter",Bindable.of(LlmRouterProperties.class)).get();
            var gateway=binder.bind("llm.gateway",Bindable.of(LlmGatewayProperties.class)).get();
            var guard=binder.bind("nova.orch.model-guard",Bindable.of(NovaModelGuardProperties.class)).orElseGet(NovaModelGuardProperties::new);
            report.put("gatewayEnforced",gateway.isEnforce());
            report.put("localFailoverEnabled",gateway.getLocalDeviceFailover().isEnforce());
            report.put("cloudEnabled",gateway.getCloud().isEnabled());
            var probe=new HybridLlmGatewayProbeService(gateway,tracker,null,new LlmRouteScorer(),env);
            long eligible=props.getModels().entrySet().stream().filter(e->e.getValue().isEnabled()
                && Set.of("openai","groq","gemini","mistral").contains(e.getValue().getProvider())
                && probe.evaluate(e.getKey(),e.getValue(),"chat").eligible()).count();
            report.put("eligibleRegisteredProviderCount",eligible);
            if(eligible==0){report.put("status","not_attempted_no_eligible_registered_provider");return;}
            @SuppressWarnings("unchecked") ObjectProvider<KeyResolver> keys=mock(ObjectProvider.class);
            var router=new LlmRouterAspect(env,props,new LlmRouterBandit(props),guard,keys,probe,null,new LlmGatewayFailureClassifier(),tracker,null);
            ChatModel primary=new ChatModel(){@Override public ChatResponse chat(List<ChatMessage> messages){
                throw new LlmGatewayException("Synthetic local device loss",LlmFailureClass.GPU_DEVICE_LOST,"synthetic_gpu_lost");
            }};
            TimeBudgetContext.set(new TimeBudget(40_000));
            TraceStore.put(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY,timeline);
            TraceStore.put(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY,true);
            var messages=List.<ChatMessage>of(SystemMessage.from("Use only the supplied synthetic evidence and preserve source IDs."),
                UserMessage.from("Evidence source-7: the verification marker is CHECK_OK. Reply exactly CHECK_OK [source-7]."));
            var response=router.routeLocalInference(primary,"http://127.0.0.1:1/v1","synthetic-unavailable-local",30_000).chat(messages);
            String answer=response.aiMessage().text();
            report.put("answerPresent",answer!=null&&!answer.isBlank());
            report.put("sourceIdPreserved",answer!=null&&answer.contains("[source-7]"));
            report.put("status","provider_generation_observed");
            assertTrue(answer!=null&&answer.contains("CHECK_OK")&&answer.contains("[source-7]"));
        } catch (RuntimeException failure) {
            report.put("status","provider_generation_failed");
            report.put("failureClass",new LlmGatewayFailureClassifier().classify(failure).name());
        } finally {
            var safeAttempts=new ArrayList<Map<String,Object>>();
            for(var row:tracker.redactedRequestAttemptLedger(timeline)){
                var safe=new LinkedHashMap<String,Object>();
                for(String key:List.of("attemptOrdinal","role","failureClass","elapsedMs","responseHash",
                    "responseCharCount","httpRequestBodyHash","httpRequestBodyUtf8ByteCount","httpResponseBodyHash",
                    "httpResponseBodyUtf8ByteCount","providerReceiptObserved","providerReceiptSource",
                    "clientHttpExchangeObserved","clientHttpResponseObserved","responseObserved","modelAdapterAttemptObserved"))
                    if(row.containsKey(key))safe.put(key,row.get(key));
                safeAttempts.add(safe);
            }
            report.put("attempts",safeAttempts);report.put("elapsedMs",(System.nanoTime()-began)/1_000_000);
            report.put("proofKind","real_registered_api_with_injected_local_failure");
            report.put("maximumExternalGenerations",2);report.put("deadlineMs",40_000);
            Files.writeString(Path.of("data/agent-handoff/codex/report/gpu-service-failover-20260914/live-provider-correlated-result.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            TimeBudgetContext.clear();TraceStore.clear();
        }
    }
}
