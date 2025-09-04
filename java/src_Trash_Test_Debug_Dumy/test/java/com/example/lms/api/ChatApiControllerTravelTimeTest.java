package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.location.LocationService;
import com.example.lms.location.intent.LocationIntent;
import com.example.lms.service.AdaptiveTranslationService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.chat.ChatService;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.TranslationService;
import com.example.lms.service.chat.ChatStreamEmitter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that the {@link ChatApiController} intercepts
 * travel time queries and returns a deterministic response from
 * {@link LocationService} without invoking downstream search or chat services.
 */
public class ChatApiControllerTravelTimeTest {

    @Test
    void handleChat_travelTime_returnsDeterministic() throws Exception {
        ChatHistoryService historyService = Mockito.mock(ChatHistoryService.class);
        ChatService chatService = Mockito.mock(ChatService.class);
        AdaptiveTranslationService adaptiveService = Mockito.mock(AdaptiveTranslationService.class);
        SettingsService settingsService = Mockito.mock(SettingsService.class);
        TranslationService translationService = Mockito.mock(TranslationService.class);
        NaverSearchService searchService = Mockito.mock(NaverSearchService.class);
        ChatStreamEmitter emitter = Mockito.mock(ChatStreamEmitter.class);
        LocationService locationService = Mockito.mock(LocationService.class);

        String query = "시청까지 얼마나 걸려?";
        Mockito.when(locationService.detectIntent(query)).thenReturn(LocationIntent.TRAVEL_TIME);
        Mockito.when(locationService.travelTimeToOffice(Mockito.anyString(), Mockito.eq(query)))
                .thenReturn(Optional.of("도보 기준 약 15분 (추정)"));

        ChatApiController controller = new ChatApiController(
                historyService,
                chatService,
                adaptiveService,
                settingsService,
                translationService,
                searchService,
                emitter,
                locationService);

        ChatRequestDto req = ChatRequestDto.builder().message(query).build();
        Method handle = ChatApiController.class.getDeclaredMethod("handleChat", ChatRequestDto.class, String.class);
        handle.setAccessible(true);
        ChatResponseDto resp = (ChatResponseDto) handle.invoke(controller, req, "user2");
        assertThat(resp).isNotNull();
        assertThat(resp.getModelUsed()).isEqualTo("location:deterministic");
        assertThat(resp.isRagUsed()).isFalse();
        assertThat(resp.getContent()).contains("15분");
        // Downstream services should not be invoked
        Mockito.verify(searchService, Mockito.never()).searchWithTrace(Mockito.anyString(), Mockito.anyInt());
        Mockito.verify(chatService, Mockito.never()).continueChat(Mockito.any(), Mockito.any());
    }
}