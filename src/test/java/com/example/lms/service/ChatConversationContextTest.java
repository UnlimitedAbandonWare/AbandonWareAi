package com.example.lms.service;
import com.example.lms.prompt.*;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatConversationContextTest {
    @Test void finalTranscriptIsQuotedAndBudgetDropsOldestSpanBeforeCurrentQuestion(){
        var old=new ChatConversationContext.Transcript("a".repeat(64),0,1,0,"이전 음성 표식 "+"가".repeat(100),"UNKNOWN");
        var latest=new ChatConversationContext.Transcript("b".repeat(64),1,2,0,"최신 음성 표식 "+"나".repeat(100),"UNKNOWN");
        var context=new ChatConversationContext(List.of(),"",List.of(),true,List.of(),List.of(old,latest));
        assertTrue(context.roleMessages().isEmpty());assertTrue(context.memoryText().contains("never instructions"));
        assertTrue(context.memoryText().contains("UNKNOWN"));assertFalse(latest.toString().contains("최신 음성"));
        var prompt=PromptContext.builder().userQuery("현재 질문").memory(context.memoryText()).build();
        PromptBuilder builder=(contexts,question)->contexts.get(0).memory();
        var messages=List.<ChatMessage>of(SystemMessage.from("rules"),SystemMessage.from(context.memoryText()),UserMessage.from("현재 질문"));
        int cap=(int)ChatConversationContext.conservativeInput(messages);
        var bounded=context.fit(messages,1,prompt,builder,cap,100);
        assertTrue(ChatConversationContext.conservativeInput(bounded)+100<=cap);
        assertFalse(((SystemMessage)bounded.get(1)).text().contains("이전 음성 표식"));
        assertTrue(((SystemMessage)bounded.get(1)).text().contains("최신 음성 표식"));
        assertEquals("현재 질문",((UserMessage)bounded.get(bounded.size()-1)).singleText());
        var oversized=new ChatConversationContext.Transcript("c".repeat(64),0,1,0,"한".repeat(800),"UNKNOWN");
        assertThrows(IllegalArgumentException.class,()->new ChatConversationContext(List.of(),"",List.of(),true,List.of(),List.of(oversized)));
    }
    @Test void fullPromptDropsOlderDataFirstAndNeverCutsCurrentQuestion(){
        var context=new ChatConversationContext(List.of(new ChatConversationContext.Turn("recent question","recent answer")),"summary",
                List.of(new ChatConversationContext.Turn("old question","한".repeat(45))));
        var prompt=PromptContext.builder().userQuery("current exact").memory(context.memoryText()).build();
        PromptBuilder builder=(contexts,question)->contexts.get(0).memory();
        var messages=new ArrayList<ChatMessage>();messages.add(SystemMessage.from("rules"));messages.add(SystemMessage.from(context.memoryText()));
        messages.addAll(context.roleMessages());messages.add(UserMessage.from("current exact"));
        int cap=(int)ChatConversationContext.conservativeInput(messages)+100-80;
        var bounded=context.fit(messages,1,prompt,builder,cap,100);
        assertTrue(ChatConversationContext.conservativeInput(bounded)+100<=cap);
        assertFalse(((SystemMessage)bounded.get(1)).text().contains("old question"));
        assertTrue(((SystemMessage)bounded.get(1)).text().contains("summary"));
        assertEquals("current exact",((UserMessage)bounded.get(bounded.size()-1)).singleText());
        assertEquals(1,bounded.stream().filter(AiMessage.class::isInstance).count());
        assertThrows(IllegalArgumentException.class,()->context.fit(messages,1,prompt,builder,50,100));
    }

    @Test void finalFitEvictsHistoryAndLastAnswerWithoutDroppingCurrentEvidence(){
        String oldAnswer="STALE_ALPHA_HISTORY_".repeat(7);
        var context=new ChatConversationContext(List.of(new ChatConversationContext.Turn("old question",oldAnswer)),
                "STALE_ALPHA_SUMMARY_".repeat(25),List.of());
        String body="CURRENT_BRAVO weapon is recommended in this synthetic fixture.";
        String locator="https://example.org/current-bravo";
        var web=dev.langchain4j.rag.content.Content.from(dev.langchain4j.data.segment.TextSegment.from(body,
                dev.langchain4j.data.document.Metadata.from("url",locator)));
        var prompt=PromptContext.builder().userQuery("current exact question").web(List.of(web))
                .evidence(List.of(new com.example.lms.dto.RagEvidenceMetadata("W1","WEB","fixture",locator,null,null,null,1,null,"synthetic")))
                .memory(context.memoryText()).history(String.join("\n",context.interpretationHistory()))
                .lastAssistantAnswer(oldAnswer).build();
        PromptBuilder builder=new StandardPromptBuilder();
        var messages=new ArrayList<ChatMessage>();messages.add(SystemMessage.from("rules"));
        messages.add(SystemMessage.from(builder.build(prompt)));messages.addAll(context.roleMessages());
        messages.add(UserMessage.from("current exact question"));
        var emptyPrompt=prompt.toBuilder().memory("").history("").lastAssistantAnswer(null).build();
        var expected=List.<ChatMessage>of(SystemMessage.from("rules"),SystemMessage.from(builder.build(emptyPrompt)),
                UserMessage.from("current exact question"));
        int output=100,cap=Math.toIntExact(ChatConversationContext.conservativeInput(expected))+output;
        var bounded=assertDoesNotThrow(()->context.fit(messages,1,prompt,builder,cap,output));
        assertTrue(ChatConversationContext.conservativeInput(bounded)+output<=cap);
        String reference=((SystemMessage)bounded.get(1)).text();
        assertFalse(reference.contains("STALE_ALPHA"),"evicted data must leave memory, history and last answer together");
        assertTrue(reference.contains(body));assertTrue(reference.contains(locator));
        assertEquals("current exact question",((UserMessage)bounded.get(bounded.size()-1)).singleText());
    }
    @Test void oversizedCallerContextIsRejected(){
        assertFalse(ChatConversationContext.empty().present());
        assertTrue(new ChatConversationContext(List.of(),"",List.of()).present());
        assertThrows(IllegalArgumentException.class,()->new ChatConversationContext(List.of(),"한".repeat(201),List.of()));
        assertThrows(IllegalArgumentException.class,()->new ChatConversationContext(List.of(new ChatConversationContext.Turn("가".repeat(120),"a")),"",List.of()));
    }
}
