package com.example.lms.assist;

import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.*;
import com.example.lms.service.chat.ChatRunRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusDisplayContractTest {
    final ObjectMapper mapper=new ObjectMapper();
    NovaFocusSettings settings(int length,boolean quick) throws Exception {
        ObjectNode node=mapper.valueToTree(NovaFocusSettings.defaults());
        node.put("answerLengthChars",length);node.put("quickAnswerEnabled",quick);
        return assertDoesNotThrow(()->mapper.treeToValue(node,NovaFocusSettings.class),"Display settings must accept generation length and quick opt-in");
    }
    @Test void defaultLengthIs400AndQuickIsOptIn(){
        var json=mapper.valueToTree(NovaFocusSettings.defaults());
        assertEquals(400,json.path("answerLengthChars").asInt());
        assertFalse(json.path("quickAnswerEnabled").asBoolean());
    }
    @Test void registeredOAuthChoiceIsAdmittedAsApiAndNeverAsLocal(){
        var adapter=new NovaFocusAnswerService(mock(ChatService.class),mock(PublicRequestBudgetGuard.class),mock(ChatRunRegistry.class));
        var choice=new ChatModelCatalogService.Choice("chatgpt-oauth:gpt-5.6-luna","chatgpt_oauth","chatgpt-oauth","gpt-5.6-luna","READY",true,"","","fixture");
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(adapter,"allowedTarget",choice,new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.API_ONLY,false,List.of())));
        assertEquals(Boolean.FALSE,ReflectionTestUtils.invokeMethod(adapter,"allowedTarget",choice,new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.LOCAL_ONLY,false,List.of())));
    }
    @Test void autoTargetNeverAdmitsLocalButLocalOnlyStillDoes(){
        var adapter=new NovaFocusAnswerService(mock(ChatService.class),mock(PublicRequestBudgetGuard.class),mock(ChatRunRegistry.class));
        var local=new ChatModelCatalogService.Choice("gemma4:26b","Ollama","local-default","gemma4:26b","READY",true,"","","fixture");
        var api=new ChatModelCatalogService.Choice("chatgpt-oauth:gpt-5.6-luna","chatgpt_oauth","chatgpt-oauth","gpt-5.6-luna","READY",true,"","","fixture");
        assertEquals(Boolean.FALSE,ReflectionTestUtils.invokeMethod(adapter,"allowedTarget",local,new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,false,List.of())));
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(adapter,"allowedTarget",local,new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.LOCAL_ONLY,false,List.of())));
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(adapter,"allowedTarget",api,new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,false,List.of())));
    }
    @Test void webCallFailureRetriesOnceParametricallyOnTheSameModel() throws Exception{
        var chat=mock(ChatService.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
        var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        when(chat.continueChat(any(),isNull(),any()))
            .thenThrow(new RuntimeException("fixture search outage"))
            .thenReturn(ChatResult.of("합성 응답","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            assertEquals("합성 응답",adapter.answer(7L,"공식 자료를 찾아 확인해줘",memory));
            verify(chat,times(2)).continueChat(request.capture(),isNull(),any());
            var calls=request.getAllValues();
            assertTrue(calls.get(0).isUseWebSearch());assertFalse(calls.get(1).isUseWebSearch());
            assertEquals(com.example.lms.gptsearch.dto.SearchMode.OFF,calls.get(1).getSearchMode());
            assertEquals(0,calls.get(1).getWebTopK());
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void numericLengthIsStrictAndRoundTrips() throws Exception {
        for(int n:List.of(80,320,400,480,800))assertEquals(n,mapper.valueToTree(settings(n,false)).path("answerLengthChars").asInt());
        for(String raw:List.of("79","801","20.5","\"\"","\"320\"","2147483648")){
            ObjectNode node=mapper.valueToTree(NovaFocusSettings.defaults());node.set("answerLengthChars",mapper.readTree(raw));
            assertThrows(Exception.class,()->mapper.treeToValue(node,NovaFocusSettings.class),raw);
        }
    }
    @Test void acceptedLengthSurvivesSettingsChangeDuringSnapshotWait() throws Exception {
        ObjectNode node=mapper.valueToTree(settings(320,false));node.set("snapshot",mapper.valueToTree(new NovaFocusSettings.Snapshot(true,"FOLD_REAR")));
        var a=mapper.treeToValue(node,NovaFocusSettings.class);var s=new NovaFocusState("fixture",a);s.open(0,"fold");
        s.input(new ConversateQuestionPolicy.Utterance("q","q",0,true,"노바 합성 질문"),0);
        assertNull(s.tick(1200,true,7));s.configure(settings(480,true),1201);
        var request=s.tick(1202,true,8);
        assertNotNull(request);
        assertEquals((Object)320,assertDoesNotThrow(()->request.getClass().getDeclaredMethod("answerLengthChars").invoke(request)));
        assertEquals(7,request.settingsVersion());assertFalse(request.quickAnswerEnabled());
    }
    @Test void lengthReachesServerPromptPolicyAndQuickClosesSearchRetry() throws Exception {
        var chat=mock(ChatService.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
        var context=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        var json=mapper.valueToTree(context);((ObjectNode)json).put("answerLengthChars",320);((ObjectNode)json).put("quickAnswerEnabled",true);
        var captured=assertDoesNotThrow(()->mapper.treeToValue(json,NovaFocusHistoryService.Context.class));
        when(chat.continueChat(any(),isNull(),any())).thenAnswer(call->{
            ChatRequestDto dto=call.getArgument(0);ChatConversationContext policy=call.getArgument(2);
            assertFalse(dto.isUseWebSearch());assertFalse(dto.isUseRag());assertEquals(0,dto.getWebTopK());
            var method=assertDoesNotThrow(()->policy.getClass().getMethod("focusAnswerLengthChars"));
            assertEquals((Object)320,assertDoesNotThrow(()->method.invoke(policy)));
            return ChatResult.of("모르겠습니다.","fixture",false);
        });
        try{assertEquals("모르겠습니다.",adapter.answer(17L,"공식 자료를 찾아 확인해줘",captured));verify(chat,times(1)).continueChat(any(),isNull(),any());}
        finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void overflowKeepsTheFullAnswerTextWithoutANotice(){
        var adapter=new NovaFocusAnswerService(mock(ChatService.class),mock(PublicRequestBudgetGuard.class),mock(ChatRunRegistry.class));
        ReflectionTestUtils.setField(adapter,"lensAnswerChars",80);
        var text="이 약의 용량은 3.5mg"+"이며 확인 없이 복용하지 않습니다".repeat(12);
        String visible=ReflectionTestUtils.invokeMethod(adapter,"lensCompact",text,ChatResult.of(text,"fixture",false));
        assertFalse(visible.contains("전체 답변"),"no notice may replace the generated answer");
        assertTrue(visible.contains("이 약의 용량은 3.5mg"));
        assertTrue(visible.endsWith("복용하지 않습니다"),"the tail of the answer is preserved");
    }
    @Test void unicodeCapAndFoldFullAnswerRemainIndependentOfRenderReceipt(){
        String unit="노👨‍👩‍👧‍👦🇰🇷é";
        assertEquals(4,NovaFocusAnswerService.graphemes(unit));
        assertEquals(1,NovaFocusAnswerService.graphemes("\r\n"));
        String full=unit.repeat(40)+" 3.5mg가 아닙니다.";
        var state=new NovaFocusState("fixture",NovaFocusSettings.defaults());state.open(0,"lens");
        state.input(new ConversateQuestionPolicy.Utterance("q","q",0,true,"노바 질문"),0);
        var accepted=state.tick(1200);var request=new NovaFocusState.Request(accepted.activationId(),accepted.requestId(),accepted.question(),null,null,accepted.sourceIds(),accepted.answerSelection(),1,80,false);
        state.accepted(request,"turn");state.answer(request,"turn",full,"a".repeat(64),1201);
        var fold=state.view(1202).forTarget("fold");var lens=state.view(1202).forTarget("lens");
        assertEquals(full,fold.answerText());assertEquals(full,lens.answerText());assertFalse(lens.hasMoreOnFold());
        assertNull(fold.renderReceiptTicket());assertEquals("a".repeat(64),lens.renderReceiptTicket());
        assertEquals(state.view(1202).answerVersion(),lens.answerVersion());
    }
    @Test void sharedPromptBoundaryHonorsFocusLengthAndLeavesOrdinaryChatAlone(){
        var builder=new com.example.lms.prompt.StandardPromptBuilder();
        var base=com.example.lms.prompt.PromptContext.builder().userQuery("합성 질문").minWordCount(500).sectionSpec(List.of("OVERVIEW","DETAILS")).build();
        assertFalse(builder.buildInstructions(base).contains("DISPLAY FOCUS OUTPUT"));
        assertTrue(builder.buildInstructions(base).contains("SECTION TEMPLATE"));
        for(int n:List.of(320,400,480)){
            var ctx=base.toBuilder().focusAnswerLengthChars(n).build();String prompt=builder.buildInstructions(ctx);
            assertTrue(prompt.contains("전체 "+n+"자 이내"));assertTrue(prompt.contains("더 짧아도"));assertTrue(prompt.contains("padding 금지"));assertFalse(prompt.contains("minimum words: 500"));
            assertFalse(prompt.contains("SECTION TEMPLATE"));
            assertEquals(n,ctx.toBuilder().build().focusAnswerLengthChars());
        }
        assertNull(ChatConversationContext.empty().focusAnswerLengthChars());
    }
}
