package com.example.lms.ensemble;
import com.example.lms.plan.PlanExecutionSpec;
import com.example.lms.prompt.*;
import com.example.lms.service.ChatConversationContext;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ContextPreparationWiringTest {
    @Test void typedServerApprovalIsClosedForAbsentMalformedOrLargerBudget(){
        assertFalse(PlanExecutionSpec.empty("none").contextPreparationAllowed());
        assertFalse(PlanExecutionSpec.parse(Map.of("context_prepare","true")).contextPreparationAllowed());
        for(int cap:List.of(1,3,4))assertFalse(PlanExecutionSpec.parse(Map.of("context_prepare",Map.of(
            "enabled",true,"purpose","attachment_comparison","max_inference_attempts",cap))).contextPreparationAllowed());
        var approved=PlanExecutionSpec.parse(Map.of("context_prepare",Map.of("enabled",true,"purpose","attachment_comparison","max_inference_attempts",2)));
        assertTrue(approved.contextPreparationAllowed());assertFalse(approved.isEmpty());
    }
    @Test void builderAndFittingKeepPacketInUserDataAndOriginalQuestionLast()throws Exception{
        var packet=(PreparedContextPacket)PreparedContextPacketTest.original();
        var ctx=PreparedContextPacketTest.seed().toBuilder().preparedContextPacket(packet).memory("quoted ".repeat(70)).build();
        assertSame(packet,ctx.toBuilder().build().preparedContextPacket());
        var builder=new StandardPromptBuilder();String text=builder.build(ctx);
        assertEquals(1,text.split("30ms가 아니라 50ms",-1).length-1);
        var conversation=new ChatConversationContext(List.of(),"quoted ".repeat(70),List.of());
        var messages=List.<ChatMessage>of(SystemMessage.from("trusted"),UserMessage.from(text),UserMessage.from("original question"));
        long reduced=ChatConversationContext.conservativeInput(List.of(SystemMessage.from("trusted"),
            UserMessage.from(builder.build(ctx.toBuilder().memory("").build())),UserMessage.from("original question")));
        var fitted=conversation.fit(messages,1,ctx,builder,(int)reduced+100,50);
        assertInstanceOf(UserMessage.class,fitted.get(1));assertTrue(((UserMessage)fitted.get(1)).singleText().contains("50ms"));
        assertEquals("original question",((UserMessage)fitted.get(fitted.size()-1)).singleText());
        assertFalse(((SystemMessage)fitted.get(0)).text().contains("50ms"));
    }
}
