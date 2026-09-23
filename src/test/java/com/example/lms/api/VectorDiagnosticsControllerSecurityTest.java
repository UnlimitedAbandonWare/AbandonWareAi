package com.example.lms.api;

import com.example.lms.config.AppSecurityConfig;
import com.example.lms.config.PineconeProps;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.service.AdminDetailsServiceImpl;
import com.example.lms.service.AdminService;
import com.example.lms.vector.EmbeddingFingerprint;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = VectorDiagnosticsController.class, useDefaultFilters = false)
@AutoConfigureMockMvc(addFilters = false)
@Import({VectorDiagnosticsController.class, ApiSecurityExceptionAdvice.class,
        AppSecurityConfig.class, AdminTokenGuardInterceptor.class})
@TestPropertySource(properties = {
        "security.force-https=false",
        "domain.allowlist.admin-token.required=false",
        "security.bootstrap-admin.password="
})
class VectorDiagnosticsControllerSecurityTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @MockBean
    private AdministratorRepository administratorRepository;

    @MockBean
    private AdminDetailsServiceImpl adminDetailsService;

    @MockBean
    private AdminService adminService;

    @MockBean
    private EmbeddingFingerprint embeddingFingerprint;

    @MockBean
    private EmbeddingStore<TextSegment> embeddingStore;

    @MockBean
    private PineconeProps pineconeProps;

    private MockMvc mvc;

    @BeforeEach
    void configureMvcWithTheRealSecurityChain() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void anonymousCannotInvokeVectorDiagnostics() throws Exception {
        mvc.perform(get("/api/vector/diagnostics"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void authenticatedNonAdminCannotInvokeVectorDiagnostics() throws Exception {
        mvc.perform(get("/api/vector/diagnostics")
                        .session(session("user-a", "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanInvokeEveryVectorDiagnosticsEndpoint() throws Exception {
        MockHttpSession admin = session("admin-a", "ROLE_ADMIN");

        mvc.perform(get("/api/vector/diagnostics").session(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/vector/upstash/info").session(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/vector/upstash/namespaces").session(admin))
                .andExpect(status().isOk());
    }

    private static MockHttpSession session(String username, String role) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        username,
                        "n/a",
                        List.of(new SimpleGrantedAuthority(role)));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        return session;
    }
}
