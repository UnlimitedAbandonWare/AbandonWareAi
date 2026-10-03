package com.example.lms.web;

import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.service.ModelSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PageControllerMainModeRoutingTest {
    MockMvc mainMode() {
        var models = mock(ModelEntityRepository.class);
        var current = mock(CurrentModelRepository.class);
        when(models.findAll()).thenReturn(List.of());
        when(current.findById(1L)).thenReturn(Optional.empty());
        var controller = new PageController(models, current, mock(ModelSettingsService.class));
        ReflectionTestUtils.setField(controller, "interviewDemo", false);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void mainHomeRedirectsToChat() throws Exception {
        mainMode().perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat"));
    }

    @Test void mainChatMappingUsesChatUiTemplate() throws Exception {
        mainMode().perform(get("/chat"))
                .andExpect(status().isOk())
                .andExpect(view().name("chat-ui"))
                .andExpect(model().attribute("chatTraceDockShell", true));
    }
}
