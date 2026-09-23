package com.example.lms.service;
import com.example.lms.assist.MemoryEvidence;
import com.example.lms.prompt.*;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatConversationEvidenceBudgetTest {
    @Test void evidenceIsDroppedAsWholeRecordsAndDoesNotBecomeDialogue(){
        Instant now=Instant.now();var e=new MemoryEvidence("id:1","id",1,"a".repeat(64),"USER_SELECTED","USER_REPORTED",
            "한글👨‍👩‍👧‍👦".repeat(15),now,now,now,null,null,null,null);
        var ctx=new ChatConversationContext(List.of(),"",List.of(),true,List.of(e));
        assertTrue(ctx.roleMessages().isEmpty());assertTrue(ctx.interpretationHistory().isEmpty());
        var prompt=PromptContext.builder().userQuery("current").memory(ctx.memoryText()).build();
        PromptBuilder builder=(c,q)->c.get(0).memory().isBlank()?"no remembered evidence":c.get(0).memory();
        var messages=List.<ChatMessage>of(SystemMessage.from("rules"),SystemMessage.from(ctx.memoryText()),UserMessage.from("current"));
        var fit=ctx.fit(messages,1,prompt,builder,400,100);
        assertTrue(ChatConversationContext.conservativeInput(fit)+100<=400);
        assertFalse(((SystemMessage)fit.get(1)).text().contains("id:1"));assertEquals("current",((UserMessage)fit.get(fit.size()-1)).singleText());
        assertThrows(IllegalArgumentException.class,()->new MemoryEvidence("e","s",1,"n","AI","VERIFIED","unproven",now,now,now,null,null,null,null));
        assertTrue(ChatConversationContext.evidenceBytes(List.of(e))<=3072);
    }
}
