package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.domain.enums.VisionMode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class FocusFoldStreamingGateTest {
    private ChatRequestDto direct(){return ChatRequestDto.builder().model("chatgpt-oauth:gpt-5.6-luna")
            .strictModelSelection(true).useVerification(false).polish(false).build();}
    private boolean allowed(ChatRequestDto dto,AnswerMode mode,boolean needsFinal){
        Boolean result=ReflectionTestUtils.invokeMethod(ChatWorkflow.class,"permitsFocusFoldStreaming",dto,mode,VisionMode.HYBRID,needsFinal);
        return Boolean.TRUE.equals(result);
    }
    @Test void strictPlainOAuthFocusAdmitsOnlyFactWithoutFinalization(){
        assertTrue(allowed(direct(),AnswerMode.FACT,false));
        assertFalse(allowed(direct(),AnswerMode.ALL_ROUNDER,false));
        assertFalse(allowed(direct(),AnswerMode.FACT,true));
        assertFalse(allowed(direct().toBuilder().strictModelSelection(false).build(),AnswerMode.FACT,false));
        assertFalse(allowed(direct().toBuilder().model("gpt-5.6-luna").build(),AnswerMode.FACT,false));
        assertFalse(allowed(direct().toBuilder().imageBase64("SYNTHETIC_IMAGE").build(),AnswerMode.FACT,false));
    }
    @Test void verificationNotRequiredIsDistinctFromUnknownOrRequired(){
        assertFalse(allowed(direct().toBuilder().useVerification(null).build(),AnswerMode.FACT,false));
        assertFalse(allowed(direct().toBuilder().useVerification(true).build(),AnswerMode.FACT,false));
        assertFalse(allowed(direct().toBuilder().polish(null).build(),AnswerMode.FACT,false));
        assertFalse(allowed(direct().toBuilder().polish(true).build(),AnswerMode.FACT,false));
    }
    @Test void freeModeCannotPublishBeforeItsWholeAnswerReplacement(){
        Boolean result=ReflectionTestUtils.invokeMethod(ChatWorkflow.class,"permitsFocusFoldStreaming",
                direct(),AnswerMode.FACT,VisionMode.FREE,false);
        assertFalse(Boolean.TRUE.equals(result));
    }
    @Test void wholeAnswerContractsRemainBuffered(){
        var processor=new com.example.lms.service.postprocess.FinalAnswerPostProcessor(new com.example.lms.service.postprocess.OutputSanitizer());
        assertFalse(processor.requiresWholeAnswer("광합성을 설명해줘"));
        assertTrue(processor.requiresWholeAnswer("self-ask A/B/C support counterexample metric neutral HOLD"));
    }
}
