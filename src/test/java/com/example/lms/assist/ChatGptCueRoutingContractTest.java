package com.example.lms.assist;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import com.example.lms.llm.ChatGptOAuthRegistration;
import com.example.lms.llm.gateway.*;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatGptCueRoutingContractTest {
    static final String ROUTE = "chatgpt-oauth:fixture-mini";
    static final String QUESTION = "하이젠베르크 불확정성 원리가 뭐야?";
    final LlmRouterProperties routes = new LlmRouterProperties();
    final MockEnvironment env = new MockEnvironment().withProperty("conversate.cue.max-request-usd", "0");
    final HybridLlmGatewayProbeService eligibility = mock(HybridLlmGatewayProbeService.class);
    final ChatGptOAuthRegistration registration = mock(ChatGptOAuthRegistration.class);
    final LlmRouterAspect router = mock(LlmRouterAspect.class);
    final UnifiedRagOrchestrator retrieval = mock(UnifiedRagOrchestrator.class);
    final ConversateLocalCardGenerator local = mock(ConversateLocalCardGenerator.class);
    final List<String> calls = new ArrayList<>();
    final List<Integer> timeouts = new ArrayList<>();
    @BeforeEach void setup() {
        routes.setEnabled(true);
        when(registration.models()).thenReturn(List.of("fixture-large", "fixture-mini"));
        var cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(true);cfg.setProvider("openai");cfg.setName("fixture-paid");
        cfg.setStage("chat");cfg.setBaseUrl("https://example.invalid/v1");cfg.setCredentialEnv("SYNTHETIC_KEY");
        routes.getModels().put("paid",cfg);
        env.withProperty("SYNTHETIC_KEY","synthetic-fixture")
                .withProperty("conversate.cue.routes.paid.input-usd-per-million","0")
                .withProperty("conversate.cue.routes.paid.output-usd-per-million","0")
                .withProperty("conversate.cue.routes.paid.gate","true");
        when(eligibility.evaluate("paid",cfg,"chat")).thenReturn(
                RoutingEligibility.eligible("paid","openai","fixture-paid","chat",1,false,Map.of()));
    }
    @AfterEach void clear() {
        com.example.lms.search.TraceStore.clear();
        com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
    }
    ConversateCueRoutingPolicy policy() {
        var policy = new ConversateCueRoutingPolicy(routes,eligibility,env,Clock.systemUTC());
        policy.setChatGptOAuth(registration);return policy;
    }
    ConversateCueRoutingPolicy.Demand demand() {
        return new ConversateCueRoutingPolicy.Demand(false,4,500,100,3000,-1);
    }
    ConversateApiCueService service() {
        var service = new ConversateApiCueService(routes,eligibility,router,retrieval,env);
        service.setChatGptOAuthRegistration(registration);
        ReflectionTestUtils.setField(service,"localSupport",
                new ConversateLocalCardGenerator((messages,schema)->"{\"choice\":0}",1000));return service;
    }
    @Test void oauthBypassesUsdAndApiKeyAdmissionWithIndependentRequestLimit() {
        env.withProperty("chatgpt.oauth.cue-daily-request-limit","1");
        var policy=policy();
        var admitted=policy.reserve(demand(),Set.of(),null,true);
        assertNotNull(admitted);assertEquals(ROUTE,admitted.choice().key());
        assertEquals(ChatGptOAuthRegistration.BILLING_SOURCE,admitted.account());
        assertFalse(admitted.verifiedFree(),"subscription is not a verified free API allowance");
        policy.complete(admitted,true,100,"none",new TokenUsage(20,10,30));
        when(registration.models()).thenReturn(List.of("fixture-next-mini"));
        assertNull(policy.reserve(demand(),Set.of(),null,true),"account request limit covers model changes");
        assertThrows(IllegalArgumentException.class,()->policy.estimate(ROUTE,20,10));
        assertEquals(Set.of("paid"),routes.getModels().keySet());
        verify(eligibility,never()).evaluate(anyString(),any(),anyString());
    }
    @Test void cooldownAndRevokedCatalogCannotBecomePaidCandidates() {
        var policy=policy();var admitted=policy.reserve(demand(),Set.of(),null,true);
        policy.complete(admitted,false,100,"GENERATION_DENIED",null);
        assertNull(policy.reserve(demand(),Set.of(),null,true));
        when(registration.models()).thenReturn(List.of());
        assertNull(policy.reserve(new ConversateCueRoutingPolicy.Demand(false,1,500,100,3000,1),
                Set.of(),new ArrayList<>(),true));
        assertTrue(policy.candidates(demand(),Set.of(ROUTE)).isEmpty());
        verify(eligibility,never()).evaluate(anyString(),any(),anyString());
    }
    @Test void registeredAccountKeepsDisplayOnConfiguredLightRoute() {
        respond(null,false);
        var result=service().answer(QUESTION,List.of(),List.of(),true);
        assertNotNull(result.card()); assertFalse(calls.isEmpty());
        assertTrue(calls.stream().noneMatch(ChatGptOAuthRegistration::isRoute));
        assertNotEquals(ChatGptOAuthRegistration.BILLING_SOURCE,result.stages().cue().get("billingSource"));
    }
    @ParameterizedTest
    @ValueSource(strings={"HTTP 503 synthetic", "synthetic auth revoked", "synthetic timeout", "malformed"})
    void utilityFailuresNeverTryOauth(String failure) {
        respond(failure,false);
        service().answer(QUESTION,List.of(),List.of(),true);
        assertFalse(calls.isEmpty());
        assertTrue(calls.stream().noneMatch(ChatGptOAuthRegistration::isRoute));
    }
    @Test void unavailableAccountDoesNotBlockOrdinaryDisplay() {
        when(registration.models()).thenReturn(List.of());
        respond(null,false);
        assertNotNull(service().answer(QUESTION,List.of(),List.of(),true).card());
        assertTrue(calls.stream().noneMatch(ChatGptOAuthRegistration::isRoute));
    }

    void respond(String failure,boolean insufficient) {
        when(router.apiAttempt(anyString(),anyInt(),anyInt(),any())).thenAnswer(invocation->{
            calls.add(invocation.getArgument(0));timeouts.add(invocation.getArgument(1));
            var model=mock(ChatModel.class);
            when(model.chat(anyList())).thenAnswer(chat->{
                if(failure!=null&&!"malformed".equals(failure))throw new IllegalStateException(failure);
                String body="malformed".equals(failure)?"not JSON":
                        "{\"text\":\"위치와 운동량의 불확실성을 동시에 줄일 수 없어요.\",\"evidenceIds\":[],\"evidenceInsufficient\":"+insufficient+"}";
                return ChatResponse.builder().aiMessage(AiMessage.from(body)).tokenUsage(new TokenUsage(20,10,30)).build();
            });
            return model;
        });
    }
}
