package com.example.lms.assist;

import com.example.lms.prompt.*;
import com.example.lms.dto.RagEvidenceMetadata;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JevPromptEvidenceContractTest {
    @org.junit.jupiter.api.AfterEach void cleanup() {
        com.example.lms.service.guard.GuardContextHolder.clear();
        com.example.lms.search.TraceStore.clear();
    }
    @Test void fakeGeneratorReceivesFullEvidenceAndSourcesAfterOrderOnlyRerank() throws Exception {
        try(var f=new JevCandidateSignalContractTest.Fixture(JevCandidateSignalContractTest.environment(),
                JevCandidateSignalContractTest::ordered)) {
            var original=JevCandidateSignalContractTest.candidates();
            var ranked=JevCandidateSignalContractTest.rerank(f,original,JevCandidateSignalContractTest.key(),
                    JevCandidateSignalContractTest.admission());
            var guard=com.example.lms.service.guard.GuardContext.defaultContext();
            guard.setMinCitations(1);
            com.example.lms.service.guard.GuardContextHolder.set(guard);
            var attribution=new com.example.lms.service.rag.RagEvidenceAttributionService(
                    null,new com.example.lms.service.guard.CitationGate(),null);
            var metadata=attribution.promoteForPromptDetailed("synthetic question",ranked,List.of(),List.of(),
                    com.example.lms.rag.model.QueryDomain.GENERAL,false).evidence();
            assertEquals(5,metadata.size());
            assertEquals("https://example.com/1",metadata.get(0).source());
            assertEquals("W1",metadata.get(0).marker());
            String prompt=new StandardPromptBuilder().build(PromptContext.builder().web(ranked).evidence(metadata).build());
            AtomicReference<String> sent=new AtomicReference<>();
            ChatModel fake=mock(ChatModel.class);
            when(fake.chat(anyString())).thenAnswer(call->{sent.set(call.getArgument(0));return "synthetic generated answer";});
            assertEquals("synthetic generated answer",fake.chat(prompt));
            for(int i=0;i<original.size();i++) {
                assertTrue(sent.get().contains(original.get(i).textSegment().text()));
                assertTrue(sent.get().contains("https://example.com/"+i));
                assertTrue(sent.get().contains("[W"+(i+1)+"]"));
                assertEquals("source-"+i,original.get(i).textSegment().metadata().getString("sourceId"));
                assertEquals(i+1,original.get(i).textSegment().metadata().getInteger("citationNumber"));
            }
        }
    }
}
