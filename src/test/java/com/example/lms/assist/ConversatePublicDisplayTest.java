package com.example.lms.assist;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversatePublicDisplayTest {
    @Test void publicEntryExcludesPrivateRetrievalAndKeepsSharedEphemeralPipeline(){
        var chat=mock(ChatService.class);var p=new ConversateAnswerPipeline();p.sharedRag(chat);
        when(chat.continueChat(any(),any())).thenAnswer(call->{ChatRequestDto req=call.getArgument(0);return ChatResult.of(Boolean.TRUE.equals(req.getUseRag())?"PRIVATE_SENTINEL":"공개 답변","local",false);});
        var answer=p.answerPublicDisplay("이 내용을 요약해 줘",List.of("이 소유자의 현재 대화"),System.currentTimeMillis(),"public-test");
        var request=ArgumentCaptor.forClass(ChatRequestDto.class);verify(chat).continueChat(request.capture(),any());
        assertEquals("ephemeral",request.getValue().getMemoryMode());assertNull(request.getValue().getSessionId());
        assertEquals(Boolean.FALSE,request.getValue().getUseRag());assertEquals(Boolean.FALSE,request.getValue().getUseWebSearch());
        assertEquals(SearchMode.OFF,request.getValue().getSearchMode());
        assertFalse(request.getValue().isUnderstandingEnabled());assertEquals(192,request.getValue().getMaxTokens());
        assertEquals("공개 답변",answer.card().text());assertFalse(answer.card().text().contains("PRIVATE_SENTINEL"));
    }
    @Test void selfContainedArithmeticUsesNormalSharedModelWithoutRetrieval(){
        for(String question:List.of("2 더하기 2의 답을 숫자 한 개로만 알려줘","2 더하기 2는 얼마야?","2 + 2?")){
            var chat=mock(ChatService.class);var pipeline=new ConversateAnswerPipeline();pipeline.sharedRag(chat);
            when(chat.continueChat(any(),any())).thenReturn(ChatResult.of("4","synthetic-model",false));
            var answer=pipeline.answerPublicDisplay(question,List.of(),0,"arithmetic-test");
            var request=ArgumentCaptor.forClass(ChatRequestDto.class);verify(chat).continueChat(request.capture(),any());
            assertEquals(question,request.getValue().getMessage());assertEquals(Boolean.FALSE,request.getValue().getUseRag());
            assertEquals(Boolean.FALSE,request.getValue().getUseWebSearch());assertEquals(SearchMode.OFF,request.getValue().getSearchMode());
            assertNull(request.getValue().getSessionId());assertEquals("ephemeral",request.getValue().getMemoryMode());
            assertEquals("4",answer.card().text());
        }
    }
    @Test void generalDefinitionQuestionsDoNotBecomeWebSearches(){
        for(String question:List.of("검색 증강 생성이 무엇인가요?","광합성은 무엇인가요?","What is retrieval augmented generation?")){
            var chat=mock(ChatService.class);var pipeline=new ConversateAnswerPipeline();pipeline.sharedRag(chat);
            when(chat.continueChat(any(),any())).thenReturn(ChatResult.of("직접 설명한 합성 답변","synthetic-model",false));
            var answer=pipeline.answerPublicDisplay(question,List.of(),0,"definition-test");
            var request=ArgumentCaptor.forClass(ChatRequestDto.class);verify(chat).continueChat(request.capture(),any());
            assertEquals(question,request.getValue().getMessage());
            assertEquals(Boolean.FALSE,request.getValue().getUseRag());
            assertEquals(Boolean.FALSE,request.getValue().getUseWebSearch(),question);
            assertEquals(SearchMode.OFF,request.getValue().getSearchMode());
            assertEquals(192,request.getValue().getMaxTokens());
            assertEquals("직접 설명한 합성 답변",answer.card().text());
        }
    }
    @Test void publicSourceRecencyAndUrlRequestsKeepWebAndExcludePrivateRetrieval(){
        for(String question:List.of("공식 출처로 답해 줘","현재 서울 날씨","웹에서 자료를 찾아줘","https://example.com 내용을 요약해 줘","2 더하기 2의 근거를 알려줘")){
            var chat=mock(ChatService.class);var pipeline=new ConversateAnswerPipeline();pipeline.sharedRag(chat);
            when(chat.continueChat(any(),any())).thenReturn(ChatResult.of("합성 답변","synthetic-model",false));
            pipeline.answerPublicDisplay(question,List.of(),0,"source-test");
            var request=ArgumentCaptor.forClass(ChatRequestDto.class);verify(chat).continueChat(request.capture(),any());
            assertEquals(Boolean.TRUE,request.getValue().getUseWebSearch());assertEquals(Boolean.FALSE,request.getValue().getUseRag());
            assertNull(request.getValue().getSessionId());assertEquals("ephemeral",request.getValue().getMemoryMode());
        }
    }
    @Test void privateLiveEntryKeepsExistingRetrievalSelection(){
        var chat=mock(ChatService.class);var p=new ConversateAnswerPipeline();p.sharedRag(chat);
        when(chat.continueChat(any(),any())).thenReturn(ChatResult.of("기존 답변","local",true));
        p.answerLive("질문?",List.of(),List.of(),0,"phone_voice","private-test");
        var req=ArgumentCaptor.forClass(ChatRequestDto.class);verify(chat).continueChat(req.capture(),any());assertTrue(req.getValue().getUseRag());assertEquals("voice",req.getValue().getInputType());
    }
    @Test void explicitCommitHandlesNonQuestionWhileAmbientPathStillSuppressesIt(){
        var input=new ConversateQuestionPolicy.Utterance("one","one",1,true,"짧게 요약해 줘");
        assertEquals("NEW_INFORMATION",new ConversateQuestionPolicy().accept(input).kind());
        var policy=new ConversateQuestionPolicy();assertEquals("QUESTION",policy.acceptExplicit(input).kind());assertEquals("DUPLICATE",policy.acceptExplicit(input).kind());
        assertEquals("PARTIAL",policy.acceptExplicit(new ConversateQuestionPolicy.Utterance("two","two",0,false,"작성 중")).kind());
        assertEquals("BACKCHANNEL",policy.acceptExplicit(new ConversateQuestionPolicy.Utterance("three","three",1,true," ")).kind());
    }
    @Test void publicSessionIgnoresAsrAndRetainsOneDispatchForDuplicateFinal() throws Exception {
        var chat=mock(ChatService.class);var pipeline=new ConversateAnswerPipeline();pipeline.sharedRag(chat);
        when(chat.continueChat(any(),any())).thenReturn(ChatResult.of("짧은 답변","local",false));
        try(var s=new ConversateSessionService(Clock.systemUTC(),pipeline)){
            String owner="a".repeat(64);var start=s.startPublicDisplay(owner);var u=new ConversateQuestionPolicy.Utterance("one","one",1,true,"요약해 줘");
            s.submit(owner,start.assistId(),start.epoch(),u,"glasses_input","one");s.submit(owner,start.assistId(),start.epoch(),u,"glasses_input","one");
            long end=System.nanoTime()+2_000_000_000L;ConversateSessionService.Snapshot state;
            do{state=s.status(owner,start.assistId());if(state.metrics().inFlight()==0)break;Thread.sleep(10);}while(System.nanoTime()<end);
            verify(chat,times(1)).continueChat(any(),any());assertEquals(1,state.metrics().duplicates());assertEquals("OFF",state.audio().state());assertNotNull(state.card());
        }
    }
}
