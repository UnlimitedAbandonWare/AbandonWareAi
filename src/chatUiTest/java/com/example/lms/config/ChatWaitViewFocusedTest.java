package com.example.lms.config;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatWaitViewFocusedTest {
    @Test
    void actualClasspathViewPublishesConfiguredRequestCapIncludingOperatorReduction() throws Exception {
        for (long cap : new long[]{240_000L, 30_000L}) {
            String html = render(Map.of("chatRequestBudgetMs", cap));
            assertEquals(Long.toString(cap), Jsoup.parse(html)
                    .selectFirst("meta[name=chat-request-budget-ms]").attr("content"));
            assertFalse(html.contains("${chatRequestBudgetMs}"));
        }
    }

    @Test
    void missingBudgetDoesNotInventAnUnlimitedClientBudget() throws Exception {
        assertEquals("", Jsoup.parse(render(Map.of()))
                .selectFirst("meta[name=chat-request-budget-ms]").attr("content"));
    }

    private static String render(Map<String, ?> model) throws Exception {
        var view = new ChatUiViewConfig().chatUiResourceViewResolver()
                .resolveViewName("chat-ui", Locale.ROOT);
        var response = new MockHttpServletResponse();
        view.render(model, new MockHttpServletRequest("GET", "/chat"), response);
        return response.getContentAsString();
    }
}
