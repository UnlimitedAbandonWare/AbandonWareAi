package com.example.lms.config;

import com.example.lms.entity.ModelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUiViewConfigFocusedTest {

    @Test
    void chatUiViewRendersPreparedModelDataWithoutTemplatePlaceholders() throws Exception {
        ChatUiViewConfig config = new ChatUiViewConfig();
        var view = config.chatUiResourceViewResolver().resolveViewName("chat-ui", Locale.KOREA);

        ModelEntity gemma = model("gemma4:26b");
        ModelEntity qwen = model("qwen3:30b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        view.render(Map.of(
                        "models", List.of(gemma, qwen),
                        "currentModel", "qwen3:30b"
                ),
                new MockHttpServletRequest("GET", "/chat-ui"),
                response);

        String html = response.getContentAsString();
        assertTrue(html.contains("<strong data-current-model>qwen3:30b</strong>"));
        assertTrue(html.contains("<option value=\"gemma4:26b\">gemma4:26b</option>"));
        assertTrue(html.contains("<option value=\"qwen3:30b\" selected>qwen3:30b</option>"));
        assertFalse(html.contains(">model</option>"));
        assertFalse(html.contains("th:each"));
        assertFalse(html.contains("th:text"));
    }

    private static ModelEntity model(String id) {
        ModelEntity entity = new ModelEntity();
        entity.setModelId(id);
        entity.setOwner("test");
        return entity;
    }
}
