package com.example.lms.agent;

import com.example.lms.llm.ChatModel;
import com.example.lms.service.verification.ClaimVerifierService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SynthesisServiceTest {

    @Test
    void rejectsVerificationNeededKnowledgeByDefault() {
        ChatModel chatModel = mock(ChatModel.class);
        ClaimVerifierService claimVerifier = mock(ClaimVerifierService.class);
        SynthesisService service = new SynthesisService(chatModel, claimVerifier);
        ReflectionTestUtils.setField(service, "minUrlSources", 1);
        ReflectionTestUtils.setField(service, "maxUnknownAttrRatio", 0.50);
        ReflectionTestUtils.setField(service, "unknownValueTokensCsv", "unknown,unconfirmed,n/a,na,none");
        ReflectionTestUtils.setField(service, "rejectVerificationNeeded", true);
        ReflectionTestUtils.setField(service, "verifyModel", "gemma4:26b");

        String verifiedJson = """
                {
                  "entity": "Qwen",
                  "domain": "GENERAL",
                  "attributes": { "status": "unknown" },
                  "sources": []
                }
                """;

        when(chatModel.generate(anyString(), org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(verifiedJson);
        when(claimVerifier.verifyClaims(anyString(), anyString(), anyString()))
                .thenReturn(new ClaimVerifierService.VerificationResult(verifiedJson, List.of()));

        var gap = new CuriosityTriggerService.KnowledgeGap("desc", "query", "GENERAL", "Qwen");

        assertTrue(service.synthesizeAndVerify(List.of("QUERY: query", "DESC: desc"), gap).isEmpty());
    }

    @Test
    void synthesisPromptRedactsSecretLikeGapAndRawDataBeforeModelCall() {
        ChatModel chatModel = mock(ChatModel.class);
        ClaimVerifierService claimVerifier = mock(ClaimVerifierService.class);
        SynthesisService service = new SynthesisService(chatModel, claimVerifier);
        ReflectionTestUtils.setField(service, "minUrlSources", 1);
        ReflectionTestUtils.setField(service, "maxUnknownAttrRatio", 0.50);
        ReflectionTestUtils.setField(service, "unknownValueTokensCsv", "unknown,unconfirmed,n/a,na,none");
        ReflectionTestUtils.setField(service, "rejectVerificationNeeded", true);
        ReflectionTestUtils.setField(service, "verifyModel", "gemma4:26b");

        String token = "sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        String verifiedJson = """
                {
                  "entity": "Qwen",
                  "domain": "GENERAL",
                  "attributes": { "status": "confirmed" },
                  "sources": ["https://example.test/qwen"]
                }
                """;

        when(chatModel.generate(anyString(), anyDouble(), anyInt()))
                .thenReturn(verifiedJson);
        when(claimVerifier.verifyClaims(anyString(), anyString(), anyString()))
                .thenReturn(new ClaimVerifierService.VerificationResult(verifiedJson, List.of()));

        var gap = new CuriosityTriggerService.KnowledgeGap(
                "desc " + token,
                "query " + token,
                "GENERAL " + token,
                "Qwen " + token);

        service.synthesizeAndVerify(List.of("raw evidence " + token), gap);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(prompt.capture(), anyDouble(), anyInt());
        assertFalse(prompt.getValue().contains(token));
    }
}
