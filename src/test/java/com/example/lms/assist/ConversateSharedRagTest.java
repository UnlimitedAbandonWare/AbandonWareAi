package com.example.lms.assist;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversateSharedRagTest {
    @Test void voiceUsesExistingWorkflowWithEphemeralContextAndCompleteBoundedPages() {
        var chat=mock(ChatService.class);var pipeline=new ConversateAnswerPipeline();pipeline.sharedRag(chat);
        String body="조건을 확인해야 합니다. ".repeat(24);
        when(chat.continueChat(any(),any())).thenReturn(ChatResult.of(body,"configured-model",true,Set.of("s1"),
                List.of(new RagEvidenceMetadata("s1","web","Synthetic source title","https://example.org/",null,null,null,1,null,null))));
        var result=pipeline.answerLive("어떤 차이가 있나요?",List.of("앞선 주제"),List.of(),System.currentTimeMillis(),"phone_voice","request-one");
        var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);verify(chat,times(1)).continueChat(request.capture(),any());
        assertEquals("voice",request.getValue().getInputType());assertEquals("ephemeral",request.getValue().getMemoryMode());assertNull(request.getValue().getSessionId());
        assertFalse(request.getValue().isUnderstandingEnabled());assertEquals("어떤 차이가 있나요?",request.getValue().getMessage());
        assertEquals("앞선 주제",request.getValue().getHistory().get(0).getContent());
        assertEquals("RAG_ANSWER",result.reason());assertEquals(body.strip(),String.join("",result.card().detailPages()));
        assertTrue(result.card().detailPages().stream().allMatch(p->p.codePointCount(0,p.length())<=120));
        assertEquals(List.of("Synthetic source title"),result.card().sourceTitles());assertEquals("request-one",result.card().requestId());
        assertEquals(0,result.generationAttempts(),"workflow delivery must not invent provider attempt evidence");
    }
    @Test void fallbackAndMissingVoiceBackendAreNotGenerationSuccess() {
        var pipeline=new ConversateAnswerPipeline();
        assertEquals("RAG_UNAVAILABLE",pipeline.answerLive("질문",List.of(),List.of(),0,"phone_voice","request-one").reason());
        var chat=mock(ChatService.class);pipeline.sharedRag(chat);
        when(chat.continueChat(any(),any())).thenReturn(ChatResult.of("확인할 근거가 부족합니다.","fallback:local",false));
        assertEquals("RAG_FALLBACK",pipeline.answerLive("질문",List.of(),List.of(),0,"phone_voice","request-one").reason());
        when(chat.continueChat(any(),any())).thenReturn(ChatResult.of("",null,false));
        assertEquals("RAG_EMPTY",pipeline.answerLive("질문",List.of(),List.of(),0,"phone_voice","request-one").reason());
    }
    @Test void explicitPreparedMaterialStillWorksWithoutAChatBackendInUnitFixture() {
        var pipeline=new ConversateAnswerPipeline();
        var r=pipeline.answerLive("보증 기간은 얼마인가요?",List.of(),List.of(new PreparedMaterialReader.Material("fixture","보증 기간은 2년입니다.")),0,"test","request-test");
        assertEquals("MATCH",r.reason());
        assertEquals("MATCH",pipeline.answerLive("보증 기간은 얼마인가요?",List.of(),List.of(new PreparedMaterialReader.Material("fixture","보증 기간은 2년입니다.")),0,"phone_voice","request-test").reason());
    }
}
