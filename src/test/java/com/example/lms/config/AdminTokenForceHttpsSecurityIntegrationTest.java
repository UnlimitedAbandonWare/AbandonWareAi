package com.example.lms.config;

import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.service.AdminDetailsServiceImpl;
import com.example.lms.service.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

@WebMvcTest(controllers = AdminTokenForceHttpsSecurityIntegrationTest.AdminProbeController.class,
        useDefaultFilters = false)
@AutoConfigureMockMvc(addFilters = false)
@Import({AdminTokenForceHttpsSecurityIntegrationTest.AdminProbeController.class,
        CustomSecurityConfig.class, AdminTokenGuardInterceptor.class})
@TestPropertySource(properties = {
        "security.force-https=true",
        "domain.allowlist.admin-token=integration-admin-token",
        "domain.allowlist.admin-token.required=true",
        "security.remember-me-key=integration-remember-me-key",
        "server.http-port=80",
        "server.https-port=443"
})
class AdminTokenForceHttpsSecurityIntegrationTest {

    private MockMvc mvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @MockBean
    private AdminDetailsServiceImpl adminDetailsService;

    @MockBean
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void forceHttpsAdminChainPreservesHeaderAuthentication() throws Exception {
        mvc.perform(get("/api/admin/probe")
                        .secure(true)
                        .header(AdminTokenGuardInterceptor.HEADER, "integration-admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void insecureRequestCannotUseSpoofedForwardedProtoToBypassRedirect() throws Exception {
        mvc.perform(get("/api/admin/probe")
                        .secure(false)
                        .header("X-Forwarded-Proto", "https")
                        .header(AdminTokenGuardInterceptor.HEADER, "integration-admin-token"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void forceHttpsChainPreservesHeaderAuthenticatedGraphWrite() throws Exception {
        mvc.perform(post("/api/admin/graph/probe")
                        .secure(true)
                        .header(AdminTokenGuardInterceptor.HEADER, "integration-admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("written"));
    }

    @Test
    void browserAdminSessionCannotPostGraphWithoutCsrfToken() throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        mvc.perform(post("/api/admin/graph/probe")
                        .secure(true)
                        .session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void forceHttpsChainPreservesDashboardHeaderAuthentication() throws Exception {
        mvc.perform(get("/dashboard/probe")
                        .secure(true)
                        .header(AdminTokenGuardInterceptor.HEADER, "integration-admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("dashboard"));
    }

    @Test
    void actualPostLogoutRevokesCopiedAdminCapability() throws Exception {
        Cookie issued = mvc.perform(get("/api/admin/probe")
                        .secure(true)
                        .header(AdminTokenGuardInterceptor.HEADER, "integration-admin-token"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(AdminTokenGuardInterceptor.COOKIE_NAME);
        assertNotNull(issued);

        Cookie copied = new Cookie(issued.getName(), issued.getValue());
        mvc.perform(post("/logout")
                        .secure(true)
                        .cookie(issued, new Cookie("XSRF-TOKEN", "logout-csrf-token"))
                        .header("X-XSRF-TOKEN", "logout-csrf-token"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/api/admin/probe")
                        .secure(true)
                        .cookie(copied))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfProtectedAdminUiWriteAcceptsIssuedCapability() throws Exception {
        Cookie issued = mvc.perform(get("/api/admin/probe")
                        .secure(true)
                        .header(AdminTokenGuardInterceptor.HEADER, "integration-admin-token"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(AdminTokenGuardInterceptor.COOKIE_NAME);
        assertNotNull(issued);

        mvc.perform(post("/model-settings/save-probe")
                        .secure(true)
                        .cookie(issued, new Cookie("XSRF-TOKEN", "ui-csrf-token"))
                        .header("X-XSRF-TOKEN", "ui-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("saved"));
    }

    @RestController
    static class AdminProbeController {
        @GetMapping("/api/admin/probe")
        String probe() {
            return "ok";
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/admin/graph/probe")
        String writeProbe() {
            return "written";
        }

        @GetMapping("/dashboard/probe")
        String dashboardProbe() {
            return "dashboard";
        }

        @org.springframework.web.bind.annotation.PostMapping("/model-settings/save-probe")
        String saveProbe() {
            return "saved";
        }
    }
}
