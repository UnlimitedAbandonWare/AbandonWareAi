package com.example.lms.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.view.AbstractView;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SettingsPageControllerTest {
    @Test void settingsReturnsOnlyTheSettingsView() throws Exception {
        var mvc = standaloneSetup(new SettingsPageController()).setViewResolvers(
                new com.example.lms.config.ChatUiViewConfig().chatUiResourceViewResolver()).build();
        mvc.perform(get("/settings")).andExpect(status().isOk()).andExpect(view().name("settings"))
                .andExpect(model().size(0)).andExpect(content().string(org.hamcrest.Matchers.containsString("settings-routing.js")));
    }
    @Test void settingsUsesTheExistingCsrfProjection() throws Exception {
        var view=new com.example.lms.config.ChatUiViewConfig().chatUiResourceViewResolver().resolveViewName("settings",java.util.Locale.KOREA);
        var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/settings");
        request.setAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName(),new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","synthetic"));
        var response=new org.springframework.mock.web.MockHttpServletResponse();view.render(Map.of(),request,response);
        org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsString().contains("name=\"_csrf_header\" content=\"X-CSRF-TOKEN\""));
        org.junit.jupiter.api.Assertions.assertFalse(response.getContentAsString().contains("th:content"));
    }
}
