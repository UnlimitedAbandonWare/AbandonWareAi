package com.example.lms.llm;

import com.abandonware.ai.addons.budget.*;
import com.example.lms.search.TraceStore;
import com.example.lms.service.verification.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.*;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;

public class JudgeCallObservationTest {
    @AfterEach void cleanup(){TraceStore.clear();TimeBudgetContext.clear();}
    public static ChatResponse response(String text,String model){
        return ChatResponse.builder().aiMessage(AiMessage.from(text))
                .metadata(ChatResponseMetadata.builder().modelName(model).build()).build();
    }
    public static ChatModel model(Callable<ChatResponse> call){
        return new ChatModel(){
            private ChatResponse invoke(){try{return call.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw new RuntimeException(e);}}
            @Override public ChatResponse chat(List<ChatMessage> messages){return invoke();}
            @Override public ChatResponse chat(ChatMessage... messages){return invoke();}
        };
    }
    public static FactVerificationStatus fact(ChatModel model){
        var beans=new StaticListableBeanFactory();if(model!=null)beans.addBean("judgeChatModel",model);
        return new FactStatusClassifier(beans.getBeanProvider(ChatModel.class)).classify("library policy",
                "library policy "+"synthetic supporting context ".repeat(8),"supported draft","requested-label");
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> receipt(String lane){
        Map<?,?> lanes=assertInstanceOf(Map.class,TraceStore.get("judge.call.observations"));
        return (Map<String,Object>)assertInstanceOf(Map.class,lanes.get(lane));
    }
    @Test void missingProviderHasKnownZeroWireAttempts(){
        assertEquals(FactVerificationStatus.PASS,fact(null));var r=receipt("fact_status_classifier");
        assertEquals(false,r.get("invocationStarted"));assertEquals(0,r.get("httpAttemptCount"));
        assertEquals("judge_model_unavailable",r.get("reason"));assertEquals("heuristic",r.get("verdictSource"));assertEquals(false,r.get("outcomeKnown"));
    }
    @Test void typedSuccessPreservesModelWithoutInventingTransportOrProvider(){
        fact(model(()->response("PASS","judge-J")));var r=receipt("fact_status_classifier");
        assertEquals(true,r.get("invocationStarted"));assertEquals("judge-J",r.get("responseModel"));
        assertNull(r.get("httpAttemptCount"));assertNull(r.get("httpStatus"));assertNull(r.get("observedProvider"));
        assertEquals("judge",r.get("verdictSource"));assertEquals(true,r.get("outcomeKnown"));
    }
    @ParameterizedTest @ValueSource(ints={401,403,429}) void typedHttpFailureRetainsOnlyNumericStatus(int status){
        fact(model(()->{throw new dev.langchain4j.exception.HttpException(status,"synthetic-private-body");}));
        var r=receipt("fact_status_classifier");assertEquals(status,r.get("httpStatus"));assertEquals(false,r.get("outcomeKnown"));
        assertNull(r.get("responseModel"));assertFalse(r.toString().contains("synthetic-private-body"));
    }
    @Test void malformedLabelUsesHeuristicAndUnknownJudgeOutcome(){
        assertEquals(FactVerificationStatus.PASS,fact(model(()->response("unparseable","judge-J"))));
        var r=receipt("fact_status_classifier");assertEquals(false,r.get("outcomeKnown"));assertEquals("heuristic",r.get("verdictSource"));
        assertEquals("judge_malformed_response",r.get("reason"));
    }
    @Test void blankResponseIsUnknownWithReceivedMetadata() {
        fact(model(() -> response("   ", "judge-J")));
        var receipt = receipt("fact_status_classifier");
        assertEquals(false, receipt.get("outcomeKnown"));
        assertEquals("judge-J", receipt.get("responseModel"));
        assertEquals("judge_empty_response", receipt.get("reason"));
    }

    @Test void nestedReceiptScopeRestoresOuterAndRemovesAfterException() {
        var outer = JudgeCallObservation.start("claim_extraction");
        var inner = JudgeCallObservation.start("claim_judgment");
        assertNull(JudgeCallObservation.current());
        try (var ignored = outer.bind()) {
            assertSame(outer, JudgeCallObservation.current());
            assertThrows(IllegalStateException.class, () -> {
                try (var nested = inner.bind()) {
                    assertSame(inner, JudgeCallObservation.current());
                    throw new IllegalStateException("synthetic");
                }
            });
            assertSame(outer, JudgeCallObservation.current());
        }
        assertNull(JudgeCallObservation.current());
    }
    @Test void expiredBudgetStopsBeforeModelInvocation(){
        TimeBudgetContext.set(new TimeBudget(0));
        fact(model(()->{fail("model must not run");return null;}));var r=receipt("fact_status_classifier");
        assertEquals(false,r.get("invocationStarted"));assertEquals(0,r.get("httpAttemptCount"));
    }
    @Test void nextCallDoesNotReusePreviousLaneMetadata(){
        fact(model(()->response("PASS","judge-J")));Object first=receipt("fact_status_classifier").get("attemptId");
        fact(null);var r=receipt("fact_status_classifier");assertNotEquals(first,r.get("attemptId"));assertNull(r.get("responseModel"));
    }
}
