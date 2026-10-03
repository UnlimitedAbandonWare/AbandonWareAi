package com.example.lms.llm;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ContextPreparationAttemptBudgetTest {
    static ChatModel wrapped(ModelRuntimeHealthTracker tracker,String role,AtomicInteger calls){
        ChatModel delegate=new ChatModel(){@Override public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request){calls.incrementAndGet();return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();}};
        return tracker.decorateRequestAttempt(delegate,role,tracker.redactedRequestAttemptRoute("synthetic","synthetic","http://127.0.0.1:1","test"),Map.of());
    }
    @Test void helperIsOnceAndKeepsFinalSlot(){
        var tracker=new ModelRuntimeHealthTracker();String id=tracker.beginRequestTimeline("request","session");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY,id);tracker.limitRequestInferenceAttempts(id,2);
        try{
            var calls=new AtomicInteger();var helper=wrapped(tracker,"context_prepare",calls);
            helper.chat(UserMessage.from("data"));
            assertThrows(RuntimeException.class,()->helper.chat(UserMessage.from("duplicate")));
            wrapped(tracker,"primary",calls).chat(UserMessage.from("question"));assertEquals(2,calls.get());
        }finally{TraceStore.clear();}
    }
    @Test void earlierModelAttemptLeavesNoAuxiliarySlot(){
        var tracker=new ModelRuntimeHealthTracker();String id=tracker.beginRequestTimeline("request","session");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY,id);tracker.limitRequestInferenceAttempts(id,2);
        try{
            var calls=new AtomicInteger();wrapped(tracker,"rewriter",calls).chat(UserMessage.from("query"));
            assertThrows(RuntimeException.class,()->wrapped(tracker,"context_prepare",calls).chat(UserMessage.from("data")));
            wrapped(tracker,"primary",calls).chat(UserMessage.from("question"));assertEquals(2,calls.get());
        }finally{TraceStore.clear();}
    }
}

