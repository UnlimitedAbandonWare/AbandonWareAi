package com.example.lms.security;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatOpenSecurityConfigTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void defaultCorsDoesNotPublishWildcardCredentials() {
        ChatOpenSecurityConfig config = new ChatOpenSecurityConfig();
        ReflectionTestUtils.setField(config, "corsAllowCredentials", false);
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "");
        ReflectionTestUtils.setField(config, "corsAllowedOriginPatterns", "");

        CorsConfiguration cors = cors(config);

        assertFalse(Boolean.TRUE.equals(cors.getAllowCredentials()));
        assertNull(cors.getAllowedOrigins());
        assertNull(cors.getAllowedOriginPatterns());
    }

    @Test
    void explicitCorsOriginsAreBounded() {
        ChatOpenSecurityConfig config = new ChatOpenSecurityConfig();
        ReflectionTestUtils.setField(config, "corsAllowCredentials", true);
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "https://admin.example.test");
        ReflectionTestUtils.setField(config, "corsAllowedOriginPatterns", "https://*.example.test");

        CorsConfiguration cors = cors(config);

        assertTrue(Boolean.TRUE.equals(cors.getAllowCredentials()));
        assertEquals("https://admin.example.test", cors.getAllowedOrigins().get(0));
        assertEquals("https://*.example.test", cors.getAllowedOriginPatterns().get(0));
    }

    @Test
    void corsOriginsNormalizePublicBaseUrlsToBrowserOrigins() {
        ChatOpenSecurityConfig config = new ChatOpenSecurityConfig();
        ReflectionTestUtils.setField(config, "corsAllowCredentials", true);
        ReflectionTestUtils.setField(config, "corsAllowedOrigins",
                "https://abandonwareai.kro.kr/, https://abandonwareai.kro.kr/chat");
        ReflectionTestUtils.setField(config, "corsAllowedOriginPatterns", "");

        CorsConfiguration cors = cors(config);

        assertEquals(1, cors.getAllowedOrigins().size());
        assertEquals("https://abandonwareai.kro.kr", cors.getAllowedOrigins().get(0));
    }

    @Test
    void invalidCorsOriginNormalizationLeavesRedactedTraceBreadcrumb() {
        String normalized = ChatOpenSecurityConfig.normalizeCorsOrigin("https://%");

        assertEquals("https://%", normalized);
        assertEquals("cors.origin", TraceStore.get("security.cors.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("security.cors.suppressed.errorType"));
        assertEquals(true, TraceStore.get("security.cors.suppressed.cors.origin"));
        assertEquals(9, TraceStore.get("security.cors.suppressed.inputLength"));
        assertTrue(TraceStore.get("security.cors.suppressed.inputHash") instanceof String);
        assertNull(TraceStore.get("security.cors.suppressed.rawInput"));
    }

    @Test
    void actuatorCsrfBypassIsLimitedToHealthAndInfo() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/security/ChatOpenSecurityConfig.java"));
        String csrfBlock = source.substring(source.indexOf(".csrf(csrf -> csrf"), source.indexOf(".authorizeHttpRequests"));

        assertTrue(source.contains("AntPathRequestMatcher.antMatcher(\"/actuator/health\")"));
        assertTrue(source.contains("AntPathRequestMatcher.antMatcher(\"/actuator/info\")"));
        assertTrue(source.contains(".requestMatchers(AntPathRequestMatcher.antMatcher(\"/actuator/**\")).denyAll()"));
        assertFalse(csrfBlock.contains("AntPathRequestMatcher.antMatcher(\"/actuator/**\")"));
    }

    @Test
    void actualFocusMappingsUseTheExistingDisplayGateWithoutOpeningOtherRoutes() throws Exception {
        var config=new ChatOpenSecurityConfig();
        ReflectionTestUtils.setField(config,"publicDisplay",true);
        int count=0;
        for(var method:com.example.lms.assist.DisplayConversateController.class.getDeclaredMethods()){
            var mapping=method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
            if(mapping==null)continue;
            for(String path:mapping.value())if(path.startsWith("/api/assist/display/focus/")){
                count++;
                assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(config,"displayRequest",new MockHttpServletRequest("POST",path))),path);
                assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(config,"displayRequest",new MockHttpServletRequest("GET",path))),path);
                var filter=new ChatOpenSecurityConfig.InterviewDemoFilter(()->"",()->true,()->false);
                var request=new MockHttpServletRequest("POST",path);request.setRemoteAddr("127.0.0.1");request.addHeader("Origin","http://localhost");
                var response=new org.springframework.mock.web.MockHttpServletResponse();var chain=new org.springframework.mock.web.MockFilterChain();
                filter.doFilter(request,response,chain);assertEquals(200,response.getStatus());assertTrue(chain.getRequest()!=null);
                var crossOrigin=new MockHttpServletRequest("POST",path);crossOrigin.setRemoteAddr("127.0.0.1");crossOrigin.addHeader("Origin","https://other.example");
                var denied=new org.springframework.mock.web.MockHttpServletResponse();filter.doFilter(crossOrigin,denied,new org.springframework.mock.web.MockFilterChain());
                assertEquals(403,denied.getStatus());
            }
        }
        assertEquals(8,count);
        for(String path:java.util.List.of("/api/assist/display/focus/admin","/api/assist/display/audio/start","/api/admin","/api/diagnostics/display")){
            assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(config,"displayRequest",new MockHttpServletRequest("POST",path))),path);
        }
        ReflectionTestUtils.setField(config,"publicDisplay",false);
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(config,"displayRequest",new MockHttpServletRequest("POST","/api/assist/display/focus/open"))));
    }

    private static CorsConfiguration cors(ChatOpenSecurityConfig config) {
        CorsConfigurationSource source = config.corsConfigurationSource();
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/chat"));
    }
}
