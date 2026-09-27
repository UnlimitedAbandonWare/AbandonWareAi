package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.orchestration.OrchestrationSignals;
import com.example.lms.service.ChatService;
import com.example.lms.service.ChatWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChatVerificationPreferenceFlowContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void omittedVerificationPreferenceFallsBackToConfiguredDefault() throws Exception {
        ChatRequestDto omitted = merge("{\"message\":\"q\"}");
        ChatWorkflow workflow = mock(ChatWorkflow.class);

        setVerificationEnabled(workflow, true);
        assertTrue(shouldVerify(workflow, omitted));

        setVerificationEnabled(workflow, false);
        assertFalse(shouldVerify(workflow, omitted));
    }

    @Test
    void explicitVerificationPreferenceSurvivesSettingsMergeAndOverridesDefault() throws Exception {
        ChatRequestDto explicitTrue = merge("{\"message\":\"q\",\"useVerification\":true}");
        ChatRequestDto explicitFalse = merge("{\"message\":\"q\",\"useVerification\":false}");
        ChatWorkflow workflow = mock(ChatWorkflow.class);

        setVerificationEnabled(workflow, true);
        assertFalse(shouldVerify(workflow, explicitFalse));

        setVerificationEnabled(workflow, false);
        assertTrue(shouldVerify(workflow, explicitTrue));
    }

    @Test
    void cacheKeySeparatesOmittedAndExplicitVerificationPreferences() throws Exception {
        ChatRequestDto omitted = merge("{\"message\":\"q\"}");
        ChatRequestDto explicitTrue = merge("{\"message\":\"q\",\"useVerification\":true}");
        ChatRequestDto explicitFalse = merge("{\"message\":\"q\",\"useVerification\":false}");

        String omittedKey = ChatService.cacheKey(omitted);
        String trueKey = ChatService.cacheKey(explicitTrue);
        String falseKey = ChatService.cacheKey(explicitFalse);

        assertNotEquals(omittedKey, trueKey);
        assertNotEquals(omittedKey, falseKey);
        assertNotEquals(trueKey, falseKey);
    }

    private static ChatRequestDto merge(String json) throws Exception {
        return ChatRequestSettingsMerger.merge(
                MAPPER.readValue(json, ChatRequestDto.class),
                Map.of(),
                false,
                LoggerFactory.getLogger(ChatVerificationPreferenceFlowContractTest.class));
    }

    private static void setVerificationEnabled(ChatWorkflow workflow, boolean enabled) throws Exception {
        Field field = ChatWorkflow.class.getDeclaredField("verificationEnabled");
        field.setAccessible(true);
        field.setBoolean(workflow, enabled);
    }

    private static boolean shouldVerify(ChatWorkflow workflow, ChatRequestDto request) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "shouldVerify",
                String.class,
                ChatRequestDto.class,
                OrchestrationSignals.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(workflow, "local evidence context", request, null);
    }
}
