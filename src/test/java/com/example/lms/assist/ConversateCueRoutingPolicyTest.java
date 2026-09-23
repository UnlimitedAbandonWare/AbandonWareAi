package com.example.lms.assist;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.RoutingEligibility;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversateCueRoutingPolicyTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path proofDir;
    void verifyGroqFixture()throws Exception {
        routes.getModels().get("groq").setName("openai/gpt-oss-20b");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var proof=mapper.createObjectNode().put("plan","free")
            .put("verifiedAtMs",now).put("expiresAtMs",now+86400000).put("coordination","shared_ledger")
            .put("organizationHash","a".repeat(64)).put("keySha256",com.example.lms.agent.GroqFreeTierGuard.keyHash("synthetic-configured"));
        proof.putObject("limits").putObject("openai/gpt-oss-20b").put("rpm",30).put("rpd",1000).put("tpm",8000).put("tpd",200000);
        proof.put("ledgerPathSha256",com.example.lms.agent.GroqFreeTierGuard.ledgerPathHash("data/usage/groq-free-reservations.jsonl"));
        var path=proofDir.resolve("groq.json");java.nio.file.Files.writeString(path,mapper.writeValueAsString(proof));
        env.withProperty("groq.free-tier.evidence",path.toString());
    }
    @Test void groqNeverUsesUnverifiedOrPaidQuotaEvenWhenDollarLimitsAreDisabled() {
        route("groq",1,.15,.6,500);
        routes.getModels().get("groq").setProvider("groq");
        env.withProperty("conversate.cost.enforce-limits","false")
                .withProperty("conversate.cue.accounts.groq.observed-at-ms",""+now)
                .withProperty("conversate.cue.accounts.groq.verified-free-requests-remaining","1000");
        assertNull(policy().reserve(demand(),Set.of()), "Request-only snapshots do not prove plan, token quotas or shared accounting");
    }
    final LlmRouterProperties routes = new LlmRouterProperties();
    final MockEnvironment env = new MockEnvironment().withProperty("FIXTURE_KEY", "synthetic-configured");
    final HybridLlmGatewayProbeService eligibility = mock(HybridLlmGatewayProbeService.class);
    void route(String key, int quality, double input, double output, int latency) {
        var cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(true); cfg.setProvider("openai"); cfg.setStage("chat");
        cfg.setName("fixture-" + key); cfg.setBaseUrl("https://example.org/v1"); cfg.setCredentialEnv("FIXTURE_KEY");
        routes.getModels().put(key, cfg);
        String p = "conversate.cue.routes." + key + ".";
        env.withProperty(p+"quality", ""+quality).withProperty(p+"gate", "true")
                .withProperty(p+"input-usd-per-million", ""+input)
                .withProperty(p+"output-usd-per-million", ""+output)
                .withProperty(p+"expected-latency-ms", ""+latency);
        when(eligibility.evaluate(key,cfg,"chat")).thenReturn(
                RoutingEligibility.eligible(key,"openai",cfg.getName(),"chat",100,false,Map.of()));
    }
    ConversateApiCueService service() {
        return new ConversateApiCueService(routes,eligibility,mock(LlmRouterAspect.class),null,env);
    }
    @Test void ordinaryHintNeverPromotesToPremiumForSpeedOrQualityBonus() {
        route("cheap",1,.20,1.20,1800); route("premium",4,4,20,600);
        assertEquals(java.util.List.of("cheap"), service().candidates(false,false,Set.of(),600));
    }
    @Test void complexRequestUsesReasoningTierWithoutPremiumOnTransportFallback() {
        route("reasoning",3,.75,3.75,2200); route("premium",4,4,20,600);
        assertEquals(java.util.List.of("reasoning"), service().candidates(false,true,Set.of(),1000));
        assertTrue(service().candidates(false,true,Set.of("reasoning"),1000).isEmpty());
    }
    @Test void slowCheapModelLosesOnlyWhenItCannotMeetStageLatencyBudget() {
        route("slow",1,.01,.02,12000); route("fast",1,.2,1.2,900);
        assertEquals(java.util.List.of("fast"),service().candidates(true,false,Set.of(),600));
    }

    final long now = java.time.Instant.parse("2026-09-16T00:00:00Z").toEpochMilli();
    ConversateCueRoutingPolicy policy() {
        return new ConversateCueRoutingPolicy(routes,eligibility,env,java.time.Clock.fixed(java.time.Instant.ofEpochMilli(now),java.time.ZoneOffset.UTC));
    }
    ConversateCueRoutingPolicy.Demand demand() {
        return new ConversateCueRoutingPolicy.Demand(true,1,300,100,3000,.02);
    }
    @Test void verifiedFreeQuotaIsReservedAcrossModelsNotIndependentlyPerRoute() {
        route("a",1,.2,1.2,500); route("b",1,.2,1.2,600);
        env.withProperty("conversate.cue.accounts.openai.observed-at-ms",""+now)
                .withProperty("conversate.cue.accounts.openai.verified-free-requests-remaining","1")
                .withProperty("conversate.cue.accounts.openai.verified-balance-usd","0");
        var policy=policy(); var first=policy.reserve(demand(),Set.of());
        assertNotNull(first); assertEquals(0,first.reservedUsd());
        assertNull(policy.reserve(demand(),Set.of("a")));
    }
    @Test void staleOrFutureAllowanceNeverMakesTheCallFree() {
        route("a",1,.2,1.2,500);
        for(long timestamp:new long[]{now-300001,now+1,0}) {
            env.withProperty("conversate.cue.accounts.openai.observed-at-ms",""+timestamp)
                    .withProperty("conversate.cue.accounts.openai.verified-free-requests-remaining","100");
            var choice=policy().reserve(demand(),Set.of());
            assertEquals(.000195,choice.reservedUsd(),.000000001); assertEquals("unknown",choice.choice().quotaState());
        }
    }
    @Test void unknownUsageRetainsReservationAndAccountQuotaBlocksSiblingModels() {
        route("a",1,.2,1.2,500); route("b",1,.2,1.2,500);
        env.withProperty("conversate.cue.accounts.openai.daily-budget-usd","0.0002");
        var policy=policy(); var first=policy.reserve(demand(),Set.of());
        policy.complete(first,false,200,"API_QUOTA_EXHAUSTED",null);
        assertTrue(policy.candidates(demand(),Set.of()).isEmpty());
    }
    @Test void actualUsageCanReleaseExcessReservationButMissingUsageCannot() {
        route("a",1,.2,1.2,500);
        env.withProperty("conversate.cue.accounts.openai.daily-budget-usd","0.0002");
        var policy=policy(); var first=policy.reserve(demand(),Set.of());
        assertNull(policy.reserve(demand(),Set.of()));
        policy.complete(first,true,300,"none",new dev.langchain4j.model.output.TokenUsage(1,1,2));
        assertNotNull(policy.reserve(demand(),Set.of()));
    }
    @Test void aliasesDoNotCreateAnotherAttemptToTheSameProviderModel() {
        route("a",1,.2,1.2,500);route("b",1,.2,1.2,500);
        routes.getModels().get("b").setName(routes.getModels().get("a").getName());
        assertEquals(1,policy().candidates(demand(),Set.of()).size());
        assertTrue(policy().candidates(demand(),Set.of("a")).isEmpty());
    }
    @Test void credentialAliasesShareTheProviderAllowanceAndZeroUsageDoesNotRefund() {
        route("a",1,.2,1.2,500);route("b",1,.2,1.2,500);
        routes.getModels().get("b").setCredentialEnv("OTHER_KEY");env.withProperty("OTHER_KEY","synthetic-key");
        env.withProperty("conversate.cue.accounts.openai.daily-budget-usd","0.0002");
        var policy=policy();var first=policy.reserve(demand(),Set.of());
        policy.complete(first,true,300,"none",new dev.langchain4j.model.output.TokenUsage(0,0,0));
        assertNull(policy.reserve(demand(),Set.of("a")));
    }
    @Test void modelOverrideWithoutMatchingPricingAndOversizedContextAreExcluded() {
        route("a",1,.2,1.2,500);
        env.withProperty("conversate.cue.routes.a.priced-model","different-model");
        assertTrue(policy().candidates(demand(),Set.of()).isEmpty());
        env.withProperty("conversate.cue.routes.a.priced-model","fixture-a").withProperty("conversate.cue.routes.a.context-tokens","399");
        assertTrue(policy().candidates(demand(),Set.of()).isEmpty());
    }
    @Test void localSupportCannotReenterApiFallbackOrInventANewAnswer() {
        var generator=new ConversateLocalCardGenerator((messages,schema)->"{\"choice\":2}",1000);
        var gateway=mock(LlmRouterAspect.class);
        org.springframework.test.util.ReflectionTestUtils.setField(generator,"gateway",gateway);
        var result=generator.suggestSupport("상황을 이해하기 어렵습니다.",java.util.List.of(),now);
        assertEquals(ConversateCardPrompt.SUGGESTIONS.get(2),result.card().text());
        assertEquals(1,result.attempts()); verifyNoInteractions(gateway);
        var invalid=new ConversateLocalCardGenerator((messages,schema)->"{\"text\":\"invented assertion\"}",1000);
        assertNotEquals("SHOW",invalid.suggestSupport("도움이 필요해요",java.util.List.of(),now).card().decision());
    }
    @Test void profileBindsOfficialModelPricesAndGeminiRouterPurpose()throws Exception {
        var sources=new org.springframework.boot.env.YamlPropertySourceLoader().load("display",new org.springframework.core.io.ClassPathResource("application-meta-display.yml"));
        var base=new org.springframework.boot.env.YamlPropertySourceLoader().load("llm",new org.springframework.core.io.ClassPathResource("application-llm.yaml"));
        var profile=new MockEnvironment();for(var source:base)profile.getPropertySources().addLast(source);
        for(var source:sources)profile.getPropertySources().addFirst(source);
        var bound=org.springframework.boot.context.properties.bind.Binder.get(profile).bind("llmrouter",org.springframework.boot.context.properties.bind.Bindable.of(LlmRouterProperties.class)).get();
        assertEquals("gpt-5.6-luna",bound.getModels().get("openai-economy").getName());
        assertEquals("gpt-5.6-terra",bound.getModels().get("openai-balanced").getName());
        assertEquals("gpt-5.6-sol",bound.getModels().get("openai-premium").getName());
        assertEquals("gemini-3.5-flash-lite",bound.getModels().get("gemini-cue").getName());
        assertEquals("gemini-3.8-flash",bound.getModels().get("gemini-pro").getName());
        assertEquals("true",profile.getProperty("gemini.gateway.purpose.router.enabled"));
        assertEquals(.20,profile.getProperty("conversate.cue.routes.openai-economy.input-usd-per-million",Double.class));
        assertEquals(3,profile.getProperty("conversate.cue.routes.openai-balanced.quality",Integer.class));
        assertEquals(0,profile.getProperty("conversate.cue.accounts.openai.observed-at-ms",Integer.class));
    }
    @Test void primaryApisMeetQualityBeforeSecondaryApisAndFreshFreeQuotaCanChangeTheWinner()throws Exception{
        route("openai",1,.2,1.2,1000);route("gemini",1,.3,2.5,800);route("groq",2,.15,.6,500);
        routes.getModels().get("gemini").setProvider("gemini");routes.getModels().get("groq").setProvider("groq");
        env.withProperty("gemini.gateway.purpose.router.enabled","true");
        verifyGroqFixture();
        var hintDemand=new ConversateCueRoutingPolicy.Demand(false,1,300,100,6500,.02);
        assertEquals("openai",policy().candidates(hintDemand,Set.of()).get(0).key());
        env.withProperty("conversate.cue.accounts.gemini.observed-at-ms",""+now).withProperty("conversate.cue.accounts.gemini.verified-free-requests-remaining","1");
        assertEquals("gemini",policy().candidates(hintDemand,Set.of()).get(0).key());
        assertEquals("groq",policy().candidates(hintDemand,Set.of("openai","gemini")).get(0).key());
    }
    @Test void observationalModeIgnoresDollarBalancesButKeepsRequestLimitAndCooldown(){
        route("a",1,.2,1.2,500);
        env.withProperty("conversate.cost.enforce-limits","false")
            .withProperty("conversate.cue.accounts.openai.daily-budget-usd","0")
            .withProperty("conversate.cue.accounts.openai.observed-at-ms",""+now)
            .withProperty("conversate.cue.accounts.openai.verified-balance-usd","0")
            .withProperty("conversate.cue.routes.a.daily-request-limit","2");
        var d=new ConversateCueRoutingPolicy.Demand(true,1,300,100,3000,0);
        var p=policy();assertNotNull(p.reserve(d,Set.of()));assertNotNull(p.reserve(d,Set.of()));
        assertNull(p.reserve(d,Set.of()));
        p=policy();var attempt=p.reserve(d,Set.of());assertNotNull(attempt);
        p.complete(attempt,false,100,"GENERATION_RATE_LIMITED",null);
        assertNull(p.reserve(d,Set.of()));
    }

    @Test void observedSlowRouteLosesFirstAttemptToSlightlyPricierFastRoute(){
        route("slow",1,.15,.60,500);route("fast",1,.30,1.50,900);
        var p=policy();var demand=new ConversateCueRoutingPolicy.Demand(false,1,300,100,6500,.02);
        var first=p.reserve(demand,Set.of());assertEquals("slow",first.choice().key());
        p.complete(first,true,4000,"none",null);
        assertEquals("fast",p.candidates(demand,Set.of("slow")).get(0).key());
    }
    @Test void consecutiveFailuresEscalateCooldownUntilSuccessResetsStreak(){
        route("a",1,.2,1.2,500);
        long[] t={now};
        var mutable=new java.time.Clock(){public long millis(){return t[0];}
            public java.time.Instant instant(){return java.time.Instant.ofEpochMilli(t[0]);}
            public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            public java.time.Clock withZone(java.time.ZoneId z){return this;}};
        var p=new ConversateCueRoutingPolicy(routes,eligibility,env,mutable);
        var first=p.reserve(demand(),Set.of());p.complete(first,false,100,"GENERATION_TIMEOUT",null);
        assertNull(p.reserve(demand(),Set.of("a")));
        t[0]+=21000;var second=p.reserve(demand(),Set.of());assertNotNull(second);
        p.complete(second,false,100,"GENERATION_TIMEOUT",null);
        t[0]+=21000;assertNull(p.reserve(demand(),Set.of()));
        t[0]+=40000;var third=p.reserve(demand(),Set.of());assertNotNull(third);
        p.complete(third,false,100,"GENERATION_TIMEOUT",null);
        t[0]+=61000;assertNull(p.reserve(demand(),Set.of()));
        t[0]+=240000;var fourth=p.reserve(demand(),Set.of());assertNotNull(fourth);
        p.complete(fourth,true,100,"none",null);
        var fifth=p.reserve(demand(),Set.of());assertNotNull(fifth);
        p.complete(fifth,false,100,"GENERATION_TIMEOUT",null);
        t[0]+=21000;assertNotNull(p.reserve(demand(),Set.of()));
    }

    @Test void fastCheapGateDoesNotSpendPrimaryHintRoutesOnClassificationTimeouts()throws Exception{
        route("openai",1,.2,1.2,1000);route("groq",2,.15,.6,800);
        routes.getModels().get("groq").setProvider("groq");
        verifyGroqFixture();
        var policy=policy();
        var gate=policy.reserve(demand(),Set.of());
        assertEquals("groq",gate.choice().key());
        policy.complete(gate,true,900,"none",null);
        var hint=policy.reserve(new ConversateCueRoutingPolicy.Demand(false,1,300,100,6500,.02),Set.of());
        assertEquals("openai",hint.choice().key());
    }

}
