package com.example.lms.service;
import com.example.lms.prompt.*;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatConversationContextTest {
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
    @Test void oversizedCallerContextIsRejected(){
        assertFalse(ChatConversationContext.empty().present());
        assertTrue(new ChatConversationContext(List.of(),"",List.of()).present());
        assertThrows(IllegalArgumentException.class,()->new ChatConversationContext(List.of(),"한".repeat(201),List.of()));
        assertThrows(IllegalArgumentException.class,()->new ChatConversationContext(List.of(new ChatConversationContext.Turn("가".repeat(120),"a")),"",List.of()));
    }
}
