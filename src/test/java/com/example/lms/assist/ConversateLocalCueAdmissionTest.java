package com.example.lms.assist;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import com.example.lms.llm.gateway.*;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversateLocalCueAdmissionTest {
    final LlmRouterProperties routes=new LlmRouterProperties();
    final LlmRouterAspect router=mock(LlmRouterAspect.class);
    final HybridLlmGatewayProbeService eligibility=mock(HybridLlmGatewayProbeService.class);
    final UnifiedRagOrchestrator retrieval=mock(UnifiedRagOrchestrator.class);
    final MockEnvironment env=new MockEnvironment().withProperty("FIXTURE_KEY","synthetic-configured-key")
            .withProperty("conversate.cost.enforce-limits","false");
    final List<String> prompts=new ArrayList<>();
    ConversateApiCueService service(){return new ConversateApiCueService(routes,eligibility,router,retrieval,env);}
    void model(String answer){
        var cfg=new LlmRouterProperties.ModelConfig();cfg.setEnabled(true);cfg.setProvider("openai");cfg.setStage("chat");cfg.setBaseUrl("https://example.org/v1");
        cfg.setName("fixture");cfg.setCredentialEnv("FIXTURE_KEY");routes.getModels().put("cheap",cfg);
        env.withProperty("conversate.cue.routes.cheap.quality","1").withProperty("conversate.cue.routes.cheap.gate","true")
                .withProperty("conversate.cue.routes.cheap.input-usd-per-million","0.2")
                .withProperty("conversate.cue.routes.cheap.output-usd-per-million","1.2");
        when(eligibility.evaluate(eq("cheap"),same(cfg),eq("chat"))).thenReturn(RoutingEligibility.eligible("cheap","openai","fixture","chat",100,false,Map.of()));
        when(router.apiAttempt(anyString(),anyInt(),anyInt(),any())).thenAnswer(call->{
            var model=mock(ChatModel.class);when(model.chat(anyList())).thenAnswer(chat->{
                prompts.add(chat.getArgument(0).toString());
                return ChatResponse.builder().aiMessage(AiMessage.from(answer))
                        .tokenUsage(new dev.langchain4j.model.output.TokenUsage(90,60,150)).build();
            });return model;
        });
    }
    @Test void wordingRequestBuildsAQuestionWithoutLookingUpItsAnswer(){
        String question="상대방에게 다음 회의가 몇 시인지 정중하게 묻는 한국어 문장을 짧게 제안해 주세요.";
        assertEquals("CUE",ConversateQuestionPolicy.cueDecision(question,List.of()).decision());
        assertEquals("CUE",ConversateQuestionPolicy.cueDecision("감사를 전하는 답장을 작성해 주세요.",List.of()).decision());
        model("{\"text\":\"다음 회의는 몇 시에 시작하나요?\",\"evidenceIds\":[]}");
        var answer=service().answer(question,List.of(),List.of(),true);
        assertEquals("API_CUE",answer.reason());
        assertEquals("다음 회의는 몇 시에 시작하나요?",answer.card().text());
        assertEquals(1,prompts.size());
        verifyNoInteractions(retrieval);
    }
    @Test void wordingRequestDoesNotBypassExplicitEvidenceOrBecomeAnAmbientTrigger(){
        for(String text:List.of("현재 회의 시간을 공식 출처로 확인하고 답장을 작성해 주세요.",
                "최신 환율을 담은 문장을 제안해 주세요.",
                "파리 인구가 얼마인지 소개하는 문장을 작성해 주세요.",
                "오늘 삼성전자 주가가 얼마인지 설명하는 문장을 정중하게 만들어줘.")){
            assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision(text,List.of()).decision(),text);
        }
        assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision("다음 회의는 몇 시인가요?",List.of()).decision());
        assertEquals("NO_CUE",ConversateQuestionPolicy.cueDecision("그 문장을 제안했어요.",List.of()).decision());
        assertEquals("NO_CUE",ConversateQuestionPolicy.cueDecision("답장을 작성하지 마세요.",List.of()).decision());
    }
    @Test void explicitContextTransformationDoesNotSearchButNewFactsStillDo(){
        var context=List.of("[assistant cue] 회의는 내일 오후 세 시에 시작하며 준비물은 노트북입니다.");
        for(String question:List.of("방금 말한 내용을 요약해 줘", "위 내용을 영어로 번역해 줘", "방금 답변을 한 문장으로 정리해 줘", "그 내용 다시 설명해줘")){
            assertEquals("CUE",ConversateQuestionPolicy.cueDecision(question,context).decision(),question);
        }
        for(String question:List.of("위 내용이 최신 정보인지 확인해 줘", "위 내용의 공식 출처를 알려 줘", "그럼 오늘 서울 날씨는 어때?", "위 내용을 요약하고 미국 대통령이 누구인지 알려 줘")){
            assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision(question,context).decision(),question);
        }
        assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision("방금 말한 내용을 요약해 줘",List.of()).decision());
    }
    @Test void stableConceptsUseFastButCurrentPrivateAndConsequentialFactsKeepRetrieval(){
        for(String text:List.of("하이젠베르크 불확정성 원리가 뭐냐", "불확정성 원리의 정의를 설명하는 문장을 작성해 주세요.",
                "TCP와 UDP는 어떻게 달라?", "중력의 개념을 설명해 줘")){
            var decision=ConversateQuestionPolicy.cueDecision(text,List.of());
            assertEquals("CUE",decision.decision(),text);assertEquals("GENERAL_KNOWLEDGE",decision.reason(),text);
        }
        for(String text:List.of("우리 회사 신규 환불 정책이 뭐야?","2026년형 제품 보증 조건의 정의를 설명해줘",
                "이 약의 안전한 복용법을 쉽게 설명해줘", "최신 API 버전 차이가 뭐야?", "오늘 삼성전자 주가가 얼마야?",
                "내 비밀번호가 뭐야?", "우리 팀 비밀코드가 뭐야?", "이번 제품의 기능이 뭐야?"))
            assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision(text,List.of()).decision(),text);
        assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision("그럼 내 계좌 잔액은?",List.of("불확정성 원리가 뭐야?")).decision());
        assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision("그럼 쉽게 설명해줘",List.of("오늘 삼성전자 주가는?","[assistant cue] 일반 설명")).decision());
        assertEquals("CUE",ConversateQuestionPolicy.cueDecision("그럼 측정기를 개선하면 해결돼?",List.of("불확정성 원리가 뭐야?","[assistant cue] 위치와 운동량의 관계")).decision());
    }
    @Test void contextSummaryReachesHintWithNoSearchAndNoInventedCitation(){
        model("{\"text\":\"회의는 내일 오후 세 시에 시작하며 노트북을 준비하면 됩니다.\",\"evidenceIds\":[]}");
        var answer=service().answer("방금 말한 내용을 요약해 줘",List.of("[assistant cue] 회의는 내일 오후 세 시에 시작하며 준비물은 노트북입니다."),List.of(),true);
        assertEquals("API_CUE",answer.reason());
        assertEquals(false,answer.stages().cue().get("searchNeeded"));
        assertTrue(answer.card().text().contains("노트북"));
        verifyNoInteractions(retrieval);
    }
    @Test void explicitRecallOfSuppliedConversationSkipsSearchButFreshFactsDoNot(){
        var context=List.of("회의는 오후 세 시에 시작하고 장소는 서울역입니다.");
        assertEquals("CUE",ConversateQuestionPolicy.cueDecision("아까 말한 회의 시간은 몇 시야?",context).decision());
        assertEquals("CUE",ConversateQuestionPolicy.cueDecision("방금 말한 회의 장소가 어디야?",context).decision());
        assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision("아까 말한 회의 시간은 몇 시야?",List.of()).decision());
        assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision("아까 말한 장소의 현재 날씨가 어때?",context).decision());
    }
    @Test void acknowledgementsAndAmbientStatementsUseZeroPaidCallsEvenAfterQuestion(){
        var svc=service();
        for(String text:List.of("네, 감사합니다.","응","안녕하세요","오늘 산책을 했어요","이제 이해했어요")){
            var r=svc.answer(text,List.of("불확정성 원리가 뭐야?"),List.of(),true);
            assertEquals("NO_CUE",r.reason(),text);assertEquals(0,r.generationAttempts());
            assertEquals("local_rules",r.stages().cue().get("decisionSource"));
            assertEquals(List.of(),r.stages().cue().get("gateAttempts"));
        }
        verifyNoInteractions(router,retrieval);
    }
    @Test void completeKoreanQuestionWithoutPunctuationNeedsOneAnswerAndNoPaidGate(){
        model("{\"text\":\"위치와 운동량의 불확실성을 동시에 원하는 만큼 줄일 수 없어요. 측정기 성능이 아닌 양자 상태의 성질에서 오는 한계예요.\",\"evidenceIds\":[]}");
        var result=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();
        doc.snippet="위치와 운동량의 불확실성에는 양자 상태 자체의 한계가 있어 측정기를 개선해도 동시에 원하는 만큼 줄일 수 없다.";
        result.results=List.of(doc);when(retrieval.query(any())).thenReturn(result);
        var answer=service().answer("하이젠베르크 불확정성 원리가 뭐야",List.of(),List.of(),true);
        assertEquals("API_CUE",answer.reason());assertEquals(1,prompts.size());
        assertEquals("local_rules",answer.stages().cue().get("decisionSource"));
        assertTrue(answer.card().text().contains("위치와 운동량"));
        assertTrue(answer.card().text().contains("측정기"));
        assertEquals(90L,answer.stages().cue().get("observedInputTokens"));
        assertEquals("FAST",answer.stages().cue().get("hintPath"));verifyNoInteractions(retrieval);
    }
    @Test void followupRetainsSubjectAndLongQuestionTailInTheOnlyAnswerPrompt(){
        model("{\"text\":\"측정기를 개선해도 해결되지 않아요. 양자 상태 자체의 한계예요.\",\"evidenceIds\":[]}");
        var result=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();
        doc.snippet="불확정성 원리는 양자 상태 자체의 성질이며 측정기 정확도의 한계가 아니다.";
        result.results=List.of(doc);when(retrieval.query(any())).thenReturn(result);
        String question="그럼 측정기를 더 좋게 만들면 해결돼? "+"앞에서 말한 실험의 전제는 유지해 주세요. ".repeat(70)+"단, 측정 오류 때문이라는 설명은 빼 주세요.";
        var answer=service().answer(question,List.of("하이젠베르크 불확정성 원리가 뭐야?"),List.of(),true);
        assertEquals("API_CUE",answer.reason());assertEquals(1,prompts.size());
        assertTrue(prompts.get(0).contains("불확정성 원리"));
        assertTrue(prompts.get(0).contains("측정 오류 때문이라는 설명은 빼 주세요"));
    }
    @Test void sameWordsAtDifferentSpeechPositionsRemainDistinctQuestions(){
        var policy=new ConversateQuestionPolicy();
        var first=policy.acceptForCue(new ConversateQuestionPolicy.Utterance("dg-1","dg-1",1,true,"원리가 뭐야?"));
        assertEquals("DUPLICATE",policy.acceptForCue(new ConversateQuestionPolicy.Utterance("dg-1","dg-1",1,true,"원리가 뭐야?")).kind());
        assertEquals("DUPLICATE",policy.acceptForCue(new ConversateQuestionPolicy.Utterance("dg-1","dg-1",2,true,"원리가 뭐야?")).kind());
        assertEquals(first.kind(),policy.acceptForCue(new ConversateQuestionPolicy.Utterance("dg-2","dg-2",1,true,"원리가 뭐야?")).kind());
    }
    @Test void contextualFragmentsAndAnswersToPendingQuestionsAreNotSwallowed(){
        assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision("부산은",List.of("서울 날씨는 어때?")).decision());
        assertEquals("CUE",ConversateQuestionPolicy.cueDecision("네",List.of("[assistant cue] 적용 조건을 더 설명할까요?")).decision());
        assertEquals("RAG_CUE",ConversateQuestionPolicy.cueDecision("공식 자료를 확인해 주세요",List.of()).decision());
        assertEquals("NO_CUE",ConversateQuestionPolicy.cueDecision("오늘 산책을 했어요",List.of("서울 날씨는 어때?")).decision());
    }
    @Test void foodAfterDeviceTalkIsATopicChangeWithoutAnExplicitPhrase(){
        var decision=ConversateQuestionPolicy.cueDecision("배고픈데 돈가스 맛있는 곳 알려줘",
                List.of("메타 디스플레이 블루투스 페어링을 점검하세요"));
        assertTrue(decision.topicChanged());
        assertFalse(decision.contextRelevant());
        assertNotEquals("NO_CUE",decision.decision());
    }
    @Test void followupKeepsPriorSubject(){
        var decision=ConversateQuestionPolicy.cueDecision("그건 얼마야?",List.of("돈가스 정식 가격을 알려줘"));
        assertFalse(decision.topicChanged());
        assertTrue(decision.contextRelevant());
    }
}
