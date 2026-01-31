package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.web.ConsentExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;




import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class ConsentExceptionHandlerMvcTest {

    @Test
    void renderConsentCard_shouldInjectScopesAndTtl() throws Exception {
        var handler = new ConsentExceptionHandler(new DummyCardRenderer());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DummyController(handler))
                .setControllerAdvice(handler).build();

        mvc.perform(MockMvcRequestBuilders.get("/dummy/consent"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("SEND_MESSAGE")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("ttl_seconds")));
    }

    static class DummyController {
        private final ConsentExceptionHandler handler;
        DummyController(ConsentExceptionHandler handler){ this.handler = handler; }

        @org.springframework.web.bind.annotation.GetMapping("/dummy/consent")
        public void trigger() {
            throw new ConsentRequiredException(List.of(com.abandonware.ai.agent.tool.scope.ToolScope.SEND_MESSAGE),
                    1800, Map.of("channel","kakao"));
        }
    }
    static class DummyCardRenderer implements com.abandonware.ai.agent.web.CardRenderer {
        @Override public String render(String template, Map<String, Object> model) {
            return "{"type":"consent","scopes":""+model.get("scopes_csv")+"","ttl_seconds":"+model.get("ttl_seconds")+"}";
        }
    }
}