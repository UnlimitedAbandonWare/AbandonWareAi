package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.lms.assist.JevChoiceAdvisor.*;

class JevChoiceContractTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final List<ChoiceQuestion> QUESTIONS=List.of(WEB_NEED,COMPLEXITY);
    static final String GOOD="{\"model\":\"jev\",\"answers\":{\"webNeed\":{\"type\":\"choice\",\"choice\":\"LIGHT\",\"probabilities\":{\"NONE\":0.1,\"LIGHT\":0.9,\"DEEP\":0}},\"complexity\":{\"type\":\"choice\",\"choice\":\"SIMPLE\",\"probabilities\":{\"SIMPLE\":0.8,\"AMBIGUOUS\":0.2,\"COMPLEX\":0}}}}";
    static final String CANDIDATE_GOOD="{\"model\":\"jev\",\"answers\":{\"relevance0\":{\"choice\":\"IRRELEVANT\",\"probability\":0.99},\"relevance1\":{\"choice\":\"RELEVANT\",\"probability\":0.99}}}";
    static final String CANDIDATE_FOREIGN=CANDIDATE_GOOD.substring(0,CANDIDATE_GOOD.length()-2)
            +",\"relevance99\":{\"choice\":\"RELEVANT\",\"probability\":0.99}}}";
    // Raw JSON preserves both keys; serializing a Map would erase the defect.
    static final String CANDIDATE_DUPLICATE=CANDIDATE_GOOD.substring(0,CANDIDATE_GOOD.length()-2)
            +",\"relevance0\":{\"choice\":\"IRRELEVANT\",\"probability\":0.99}}}";
    static final String CANDIDATE_MISSING="{\"model\":\"jev\",\"answers\":{\"relevance0\":{\"choice\":\"IRRELEVANT\",\"probability\":0.99}}}";
    static final class Fixture implements AutoCloseable {
        final HttpServer server;final AtomicInteger calls=new AtomicInteger();final AtomicReference<String> body=new AtomicReference<>();
        String response=GOOD;
        Fixture()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/evaluate",exchange->{calls.incrementAndGet();body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] out=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,out.length);try(var stream=exchange.getResponseBody()){stream.write(out);}});
            server.start();
        }
        JevEvaluationRuntime.ChoiceResponse evaluate(String question,List<ChoiceQuestion> questions,int maxBytes){
            var req=new JevDecisionAdvisor.EvalRequest("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/evaluate","typesafe-ai/jev","FIXTURE",question,"main","",250,800,maxBytes,65536);
            return new JevGatewayClient(name->"synthetic").evaluateChoices(req,questions);
        }
        public void close(){server.stop(0);}
    }
    @Test void twoQuestionsUseOneTransportCall()throws Exception{
        try(var f=new Fixture()){
            var result=f.evaluate("Synthetic question",QUESTIONS,8192).result();
            assertEquals(1,f.calls.get());assertEquals(2,result.answers().size());
            assertEquals("LIGHT",result.answers().get("webNeed").choice());
            assertEquals(0.9,result.answers().get("webNeed").probability().orElseThrow(),0.00001);
            assertEquals(2,JSON.readTree(f.body.get()).path("questions").size());
        }
    }
    @Test void malformedOneQuestionKeepsOther()throws Exception{
        try(var f=new Fixture()){
            f.response=GOOD.replace("\"choice\":\"SIMPLE\"","\"choice\":\"NOT_AN_OPTION\"");
            var result=f.evaluate("Synthetic question",QUESTIONS,8192).result();
            assertEquals("LIGHT",result.answers().get("webNeed").choice());
            assertFalse(result.answers().get("complexity").schemaValid());
        }
    }
    @Test void candidateBatchRejectsForeignResponseId()throws Exception{
        assertInvalidCandidateBatch(CANDIDATE_FOREIGN);
    }
    @Test void candidateBatchRejectsRawDuplicateResponseId()throws Exception{
        assertInvalidCandidateBatch(CANDIDATE_DUPLICATE);
    }
    @Test void candidateBatchRejectsMissingResponseId()throws Exception{
        assertInvalidCandidateBatch(CANDIDATE_MISSING);
    }
    static void assertInvalidCandidateBatch(String rawResponse)throws Exception{
        try(var f=new Fixture()){
            f.response=rawResponse;
            var result=f.evaluate("synthetic",RELEVANCE.subList(0,2),8192).result();
            assertEquals("invalid_response",result.reasonCode());
            assertTrue(result.answers().isEmpty());
            assertEquals(1,f.calls.get());
        }
    }
    @Test void missingProbabilityNeverBecomesCertain()throws Exception{
        try(var f=new Fixture()){
            f.response="{\"model\":\"jev\",\"answers\":{\"webNeed\":{\"choice\":\"LIGHT\"}}}";
            var o=f.evaluate("Synthetic question",QUESTIONS,8192).result().answers().get("webNeed");
            assertTrue(o.schemaValid());assertTrue(o.probability().isEmpty());assertFalse(o.confidenceAccepted());
        }
    }
    @Test void shadowCandidateIsNotUsableLegacyAdvice(){
        try(var h=new JevPrefetchContractTest.Harness()){
            h.env.withProperty("demo.jev.mode","shadow");var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                var handle=h.prefetch(key,a);
                assertEquals("shadow",h.choice.await(handle,key,a.deadlineNanos()).reasonCode());
                h.executor.runNext();
                var o=h.choice.await(handle,key,a.deadlineNanos()).answers().get("webNeed");
                assertEquals("LIGHT",o.choice());assertFalse(o.confidenceAccepted());
                assertFalse(h.legacy.advise("cue","synthetic","CUE").usable());
                h.executor.runNext();
            }
        }
    }
    @Test void fullUtf8EnvelopeLimitEnforced()throws Exception{
        try(var f=new Fixture()){
            // The UTF-8 query alone fits 1024 bytes; the whole question/catalog envelope does not.
            assertEquals("state_oversized",f.evaluate("한".repeat(300),QUESTIONS,1024).result().reasonCode());
            assertEquals(0,f.calls.get());
        }
    }
    @Test void maskedQuestionAndNoAnswerHistoryInBody()throws Exception{
        try(var f=new Fixture()){
            String raw="email person@example.invalid phone 010-1234-5678 https://name:synthetic@example.invalid/path?q=private#part C:\\Users\\Alice\\private.txt";
            var safe=new JevQuestionSanitizer().sanitize(raw).orElseThrow();
            f.evaluate(safe,QUESTIONS,8192);
            String body=f.body.get();assertNotNull(body);
            for(String banned:List.of("person@","010-1234","synthetic@","q=private","Alice","history","answerText","sessionId","userId","localFingerprint"))
                assertFalse(body.contains(banned),banned);
            var state=JSON.readTree(body).path("state");
            assertEquals(Set.of("query","surface","externalDecisionAllowed"),JSON.convertValue(state,Map.class).keySet());
            assertTrue(new JevQuestionSanitizer().sanitize("가".repeat(1201)).isEmpty());
            assertTrue(new JevQuestionSanitizer().sanitize("가".repeat(1199)+"😀").isEmpty());
        }
    }
    @Test void unknownUsageIsNotZeroCost()throws Exception{
        try(var f=new Fixture()){
            assertTrue(f.evaluate("synthetic",QUESTIONS,8192).result().billedUsd().isEmpty());
            f.response=GOOD.substring(0,GOOD.length()-1)+",\"gateway\":{\"cost\":\"0.000012\"}}";
            var cost=f.evaluate("synthetic",QUESTIONS,8192).result().billedUsd();assertTrue(cost.isPresent());
            assertEquals(new java.math.BigDecimal("0.000012"),cost.orElseThrow());
        }
    }
    @Test void officialGatewayCostHasPrecedenceWithoutTreatingUnknownAsFree()throws Exception{
        try(var f=new Fixture()){
            for(String cost:List.of("\"0.000012\"","\"0\"")){
                f.response=GOOD.substring(0,GOOD.length()-1)
                        +",\"providerMetadata\":{\"gateway\":{\"cost\":"+cost+"}},\"gateway\":{\"cost\":\"99\"}}";
                assertEquals(new java.math.BigDecimal(cost.replace("\"","")),
                        f.evaluate("synthetic",QUESTIONS,8192).result().billedUsd().orElseThrow());
            }
            for(String cost:List.of("null","0.01","\"NaN\"","\"-0.001\"")){
                f.response=GOOD.substring(0,GOOD.length()-1)
                        +",\"providerMetadata\":{\"gateway\":{\"cost\":"+cost+"}},\"gateway\":{\"cost\":\"99\"}}";
                assertTrue(f.evaluate("synthetic",QUESTIONS,8192).result().billedUsd().isEmpty());
            }
        }
    }
    @Test void choiceOffCreatesNoSecondRuntime(){
        var runner=new ApplicationContextRunner().withInitializer(ctx->{
            // The host may set CONVERSATE_ENABLED; default tests isolate it from explicit test properties.
            ctx.getEnvironment().getPropertySources().remove("systemEnvironment");
            ctx.getEnvironment().getPropertySources().remove("systemProperties");
        }).withUserConfiguration(JevRuntimeConfiguration.class,JevDecisionAdvisor.class);
        runner.withPropertyValues("conversate.enabled=true").run(ctx->{
            assertEquals(1,ctx.getBeansOfType(JevEvaluationRuntime.class).size());
            assertEquals(0,ctx.getBeansOfType(JevChoiceAdvisor.class).size());
            assertEquals(1,ctx.getBeansOfType(JevDecisionAdvisor.class).size());
        });
        runner.withPropertyValues("conversate.enabled=false","demo.jev.choice.enabled=true").run(ctx->{
            assertEquals(1,ctx.getBeansOfType(JevEvaluationRuntime.class).size());
            assertEquals(1,ctx.getBeansOfType(JevChoiceAdvisor.class).size());
            assertEquals(0,ctx.getBeansOfType(JevDecisionAdvisor.class).size());
        });
        runner.run(ctx->assertEquals(0,ctx.getBeansOfType(JevEvaluationRuntime.class).size()));
    }
    @Test void contradictoryOrInvalidProbabilityExcludesOnlyItsQuestion()throws Exception{
        try(var f=new Fixture()){
            for(String extra:List.of("\"probability\":0.2,","\"probability\":-0.1,","\"probability\":1.1,","\"probability\":\"NaN\",")){
                f.response=GOOD.replace("\"choice\":\"LIGHT\",","\"choice\":\"LIGHT\","+extra);
                var observations=f.evaluate("synthetic",QUESTIONS,8192).result().answers();
                assertFalse(observations.get("webNeed").schemaValid());
                assertTrue(observations.get("complexity").schemaValid());
            }
        }
    }
    @Test void wrongModelInvalidatesWholeBundleAndNegativeCostStaysUnknown()throws Exception{
        try(var f=new Fixture()){
            f.response=GOOD.replace("\"model\":\"jev\"","\"model\":\"foreign-jev\"");
            var response=f.evaluate("synthetic",QUESTIONS,8192).result();
            assertEquals("wrong_model",response.reasonCode());assertTrue(response.answers().isEmpty());
            f.response=GOOD.substring(0,GOOD.length()-1)+",\"gateway\":{\"cost\":\"-0.001\"}}";
            assertTrue(f.evaluate("synthetic",QUESTIONS,8192).result().billedUsd().isEmpty());
        }
    }
    @Test void confidenceRequiresExplicitThresholdAndUsesInclusiveBoundary(){
        try(var h=new JevPrefetchContractTest.Harness()){
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                var handle=h.prefetch(key,a);h.executor.runNext();
                h.env.setProperty("demo.jev.confidence.web-light","not-a-number");
                assertFalse(h.choice.await(handle,key,a.deadlineNanos()).answers().get("webNeed").confidenceAccepted());
                h.env.setProperty("demo.jev.confidence.web-light","0.9");
                assertTrue(h.choice.await(handle,key,a.deadlineNanos()).answers().get("webNeed").confidenceAccepted());
                h.env.setProperty("demo.jev.confidence.web-light","0.91");
                assertFalse(h.choice.await(handle,key,a.deadlineNanos()).answers().get("webNeed").confidenceAccepted());
            }
        }
    }
    @Test void newFlagsMissingScopeAndPrivacyAdmissionPreventDispatch(){
        for(String flag:List.of("choice","prefetch")){
            try(var h=new JevPrefetchContractTest.Harness()){
                h.env.setProperty("demo.jev."+flag+".enabled","false");var key=h.key();var a=h.admission();
                try(var scope=JevDecisionScope.bind("main",key,a)){
                    assertEquals("disabled",h.choice.await(h.prefetch(key,a),key,a.deadlineNanos()).reasonCode());
                    assertTrue(h.executor.tasks.isEmpty());
                }
            }
        }
        try(var h=new JevPrefetchContractTest.Harness()){
            var key=h.key();var a=h.admission();
            assertEquals("disabled",h.choice.await(h.prefetch(key,a),key,a.deadlineNanos()).reasonCode());
            var denied=new JevEvaluationRuntime.DecisionAdmission(()->true,a.deadlineNanos(),false);
            try(var scope=JevDecisionScope.bind("main",key,denied)){
                var handle=h.prefetch(key,denied);assertTrue(handle.privacyBlocked());
                assertTrue(h.choice.await(handle,key,a.deadlineNanos()).answers().isEmpty());assertTrue(h.executor.tasks.isEmpty());
            }
        }
    }
    @Test void completedCandidateCannotBypassGlobalOff(){
        try(var h=new JevPrefetchContractTest.Harness()){
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                var handle=h.prefetch(key,a);h.executor.runNext();
                h.env.setProperty("demo.jev.mode","off");h.env.setProperty("demo.jev.surface.main.mode","on");
                assertTrue(h.choice.await(handle,key,a.deadlineNanos()).answers().isEmpty());
                assertEquals(1,h.calls.get());
            }
        }
    }
    @Test void sanitizerMasksResidentAndAccountGroupsWithoutErasingDates(){
        var sanitizer=new JevQuestionSanitizer();
        var safe=sanitizer.sanitize("resident 900101-1234567 account 123-456789-01-234 date 2026-09-30").orElseThrow();
        assertFalse(safe.contains("900101"));assertFalse(safe.contains("456789"));
        assertTrue(safe.contains("2026-09-30"));
    }
    @Test void sanitizerMasksUnicodeEmailAndOpaqueCredentialFamilies(){
        var sanitizer=new JevQuestionSanitizer();
        String raw="홍길동@example.invalid "+ "gsk_"+"A1b2C3d4E5f6G7h8I9j0K1l2M3n4";
        var safe=sanitizer.sanitize(raw).orElseThrow();
        assertFalse(safe.contains("홍길동@"));assertFalse(safe.contains("A1b2C3d4"));
        assertTrue(sanitizer.sanitize("broken\uD800").isEmpty());
    }
    @Test void callerControlledDescriptorsNeverReachTransport()throws Exception{
        for(var descriptor:List.of(
                new ChoiceQuestion("webNeed","previous answer: private@example.invalid",QUESTIONS.get(0).criteria()),
                new ChoiceQuestion("webNeed",QUESTIONS.get(0).instructions(),
                        Map.of("NONE","history private@example.invalid","LIGHT","localRequestId=private","DEEP","deep")))){
            try(var f=new Fixture()){
                assertEquals("invalid_response",f.evaluate("synthetic",List.of(descriptor),8192).result().reasonCode());
                assertEquals(0,f.calls.get());assertNull(f.body.get());
            }
            try(var h=new JevPrefetchContractTest.Harness()){
                var key=h.key();var admission=h.admission();
                try(var scope=JevDecisionScope.bind("main",key,admission)){
                    var handle=h.choice.prefetch(key,"main","synthetic",List.of(descriptor),admission);
                    assertEquals("invalid_response",h.choice.await(handle,key,admission.deadlineNanos()).reasonCode());
                    assertTrue(h.executor.tasks.isEmpty());
                }
            }
        }
    }
}
