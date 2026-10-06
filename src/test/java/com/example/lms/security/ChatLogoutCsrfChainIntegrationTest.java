package com.example.lms.security;

import com.example.lms.config.AppSecurityConfig;
import com.example.lms.config.ChatUiViewConfig;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.service.AdminDetailsServiceImpl;
import com.example.lms.service.AdminService;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChatLogoutCsrfChainIntegrationTest.Pages.class, useDefaultFilters = false)
@AutoConfigureMockMvc(addFilters = false)
@Import({ChatLogoutCsrfChainIntegrationTest.Pages.class, ChatUiViewConfig.class,
        ChatOpenSecurityConfig.class, AppSecurityConfig.class,
        AdminTokenGuardInterceptor.class})
@TestPropertySource(properties = {
        "demo.auth.proto-open=false",
        "security.force-https=false",
        "security.remember-me-key=synthetic-csrf-contract",
        "domain.allowlist.admin-token.required=false"
})
class ChatLogoutCsrfChainIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy security;
    @Autowired PasswordEncoder encoder;
    @MockBean AdministratorRepository administrators;
    @MockBean AdminDetailsServiceImpl adminDetails;
    @MockBean CustomUserDetailsService globalUsers;
    @MockBean AdminService adminService;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    @Test void indexFormTokenCanBeSubmittedToActualLogoutChain() throws Exception {
        var landing = mvc.perform(get("/index")).andExpect(status().isOk()).andReturn();
        var form = Jsoup.parse(landing.getResponse().getContentAsString())
                .selectFirst("form[action='/logout'] input[name='_csrf']");
        assertNotNull(form, "the existing page must render a CSRF input");
        Cookie csrf = landing.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrf, "index and logout chains must share the cookie token repository");
        mvc.perform(post("/logout").cookie(csrf).param("_csrf", form.attr("value")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test void logoutStillRejectsMissingOrMismatchedCsrf() throws Exception {
        mvc.perform(post("/logout")).andExpect(status().isForbidden());
        mvc.perform(post("/logout").cookie(new Cookie("XSRF-TOKEN", "synthetic-cookie"))
                        .param("_csrf", "different-synthetic-token"))
                .andExpect(status().isForbidden());
    }

    @Test void freshSyntheticLoginAndLogoutPreserveAdminSessionBoundary() throws Exception {
        String encodedPassword = encoder.encode("synthetic-password");
        when(adminDetails.loadUserByUsername("synthetic-admin")).thenAnswer(invocation -> User.withUsername("synthetic-admin")
                .password(encodedPassword).roles("ADMIN").build());
        Map<String, Cookie> browserCookies = new LinkedHashMap<>();
        var login = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        updateCookies(browserCookies, login);
        var loginCsrfInput = Jsoup.parse(login.getResponse().getContentAsString()).selectFirst("input[name='_csrf']");
        assertNotNull(loginCsrfInput);
        var authenticated = mvc.perform(post("/login").cookie(browserCookies.values().toArray(Cookie[]::new))
                        .param("_csrf", loginCsrfInput.attr("value"))
                        .param("username", "synthetic-admin").param("password", "synthetic-password"))
                .andExpect(redirectedUrl("/index")).andReturn();
        updateCookies(browserCookies, authenticated);
        assertNotNull(browserCookies.get("remember-me"), "alwaysRemember login must issue the browser cookie");
        var session = (MockHttpSession) authenticated.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(get("/admin/dashboard").session(session)
                .cookie(browserCookies.values().toArray(Cookie[]::new))).andExpect(status().isOk());
        var index = mvc.perform(get("/index").session(session)
                .cookie(browserCookies.values().toArray(Cookie[]::new))).andExpect(status().isOk()).andReturn();
        updateCookies(browserCookies, index);
        var logoutCsrfInput = Jsoup.parse(index.getResponse().getContentAsString()).selectFirst("input[name='_csrf']");
        assertNotNull(logoutCsrfInput);
        var loggedOut = mvc.perform(post("/logout").session(session)
                        .cookie(browserCookies.values().toArray(Cookie[]::new)).param("_csrf", logoutCsrfInput.attr("value")))
                .andExpect(redirectedUrl("/login?logout")).andReturn();
        Cookie clearedRememberMe = loggedOut.getResponse().getCookie("remember-me");
        assertNotNull(clearedRememberMe);
        assertEquals(0, clearedRememberMe.getMaxAge(), "logout must expire the remember-me cookie");
        updateCookies(browserCookies, loggedOut);
        assertFalse(browserCookies.containsKey("remember-me"));
        assertTrue(session.isInvalid(), "logout must invalidate the authenticated session");
        var afterLogout = get("/admin/dashboard");
        if (!browserCookies.isEmpty()) afterLogout.cookie(browserCookies.values().toArray(Cookie[]::new));
        mvc.perform(afterLogout).andExpect(status().isForbidden());
    }

    @Test void wrongSyntheticCredentialsCannotOpenAdminPage() throws Exception {
        when(adminDetails.loadUserByUsername("synthetic-missing"))
                .thenThrow(new UsernameNotFoundException("synthetic user absent"));
        when(globalUsers.loadUserByUsername("synthetic-missing"))
                .thenThrow(new UsernameNotFoundException("synthetic user absent"));
        Map<String, Cookie> browserCookies = new LinkedHashMap<>();
        var login = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        updateCookies(browserCookies, login);
        var wrongLoginCsrfInput = Jsoup.parse(login.getResponse().getContentAsString()).selectFirst("input[name='_csrf']");
        assertNotNull(wrongLoginCsrfInput);
        var rejected = mvc.perform(post("/login").cookie(browserCookies.values().toArray(Cookie[]::new))
                        .param("_csrf", wrongLoginCsrfInput.attr("value"))
                        .param("username", "synthetic-missing").param("password", "synthetic-invalid"))
                .andExpect(redirectedUrl("/login?error")).andReturn();
        updateCookies(browserCookies, rejected);
        var admin = get("/admin/dashboard").cookie(browserCookies.values().toArray(Cookie[]::new));
        var rejectedSession = (MockHttpSession) rejected.getRequest().getSession(false);
        if (rejectedSession != null) admin.session(rejectedSession);
        mvc.perform(admin).andExpect(status().isForbidden());
    }

    private static void updateCookies(Map<String, Cookie> browserCookies, MvcResult result) {
        for (Cookie responseCookie : result.getResponse().getCookies()) {
            if (responseCookie.getMaxAge() == 0) browserCookies.remove(responseCookie.getName());
            else browserCookies.put(responseCookie.getName(), responseCookie);
        }
    }

    @Controller static class Pages {
        @GetMapping("/index") String index() { return "index"; }
        @GetMapping("/login") String login() { return "login"; }
        @GetMapping("/admin/dashboard") @ResponseBody String admin() { return "protected"; }
    }
}
