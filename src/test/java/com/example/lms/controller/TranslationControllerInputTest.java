package com.example.lms.controller;

import com.example.lms.dto.ChatMessageDto;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.TranslationTrainingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TranslationControllerInputTest {

    @Test
    void trainNowRejectsHistoryWithOnlyNullMessagesWithoutCallingService() {
        TranslationTrainingService service = mock(TranslationTrainingService.class);
        TranslationController controller = new TranslationController(service);

        ResponseEntity<Map<String, Object>> response = controller.trainNow(new java.util.ArrayList<>(java.util.Collections.singletonList(null)));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void trainNowSkipsNullMessagesBeforeLearning() {
        TranslationTrainingService service = mock(TranslationTrainingService.class);
        when(service.learnRuleFromChatHistory(org.mockito.ArgumentMatchers.anyList())).thenReturn(1);
        TranslationController controller = new TranslationController(service);

        ResponseEntity<Map<String, Object>> response = controller.trainNow(Arrays.asList(
                new ChatMessageDto(1, "user", "hello"),
                null,
                new ChatMessageDto(2, "assistant", "안녕하세요")));

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatRequestDto.Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).learnRuleFromChatHistory(captor.capture());
        assertEquals(2, captor.getValue().size());
    }
}
