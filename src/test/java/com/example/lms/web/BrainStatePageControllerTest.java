package com.example.lms.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.view.AbstractView;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BrainStatePageControllerTest {

    @Test
    void adminBrainStatePageReturnsTemplateName() throws Exception {
        MockMvc mvc = standaloneSetup(new BrainStatePageController())
                .setSingleView(new NoopView())
                .build();

        mvc.perform(get("/admin/brain-state"))
                .andExpect(status().isOk())
                .andExpect(view().name("brain-state"));
    }

    private static final class NoopView extends AbstractView {
        @Override
        protected void renderMergedOutputModel(Map<String, Object> model,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
            response.setStatus(HttpServletResponse.SC_OK);
        }
    }
}
