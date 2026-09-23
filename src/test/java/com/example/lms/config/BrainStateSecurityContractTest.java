package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainStateSecurityContractTest {

    @Test
    void vectorAdminChainHonorsForceHttpsProperty() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/VectorAdminSecurityConfig.java"));
        int start = source.indexOf("public SecurityFilterChain vectorAdminChain");
        String chain = source.substring(start);

        assertTrue(source.contains("@Value(\"${security.force-https:false}\")"));
        assertTrue(source.contains("@Value(\"${server.http-port:80}\")"));
        assertTrue(source.contains("@Value(\"${server.https-port:443}\")"));
        assertTrue(chain.contains("if (forceHttps)"));
        assertTrue(chain.contains("http.portMapper(mapper -> mapper.http(httpPort).mapsTo(httpsPort))"));
        assertTrue(chain.contains("http.requiresChannel(channel -> channel.anyRequest().requiresSecure())"));
        assertTrue(chain.contains(".anyRequest().hasRole(\"VECTOR_ADMIN\")"));
    }

    @Test
    void brainStateWritesStayUnderVectorAdminBoundaryAndDiagnosticsAreReadOnly() throws Exception {
        String admin = Files.readString(Path.of("main/java/com/example/lms/api/BrainStateAdminController.java"));
        String diagnostics = Files.readString(Path.of("main/java/com/example/lms/api/BrainStateDiagnosticsController.java"));
        String appSecurity = Files.readString(Path.of("main/java/com/example/lms/config/AppSecurityConfig.java"));
        String vectorSecurity = Files.readString(Path.of("main/java/com/example/lms/config/VectorAdminSecurityConfig.java"));
        String adminGuard = Files.readString(Path.of("main/java/com/example/lms/config/AdminTokenGuardWebMvcConfig.java"));
        String adminFilter = Files.readString(Path.of("main/java/com/example/lms/security/AdminTokenGuardFilter.java"));
        String graphAdmin = Files.readString(Path.of("main/java/com/example/lms/graphdb/GraphDbManualLearningController.java"));

        assertTrue(admin.contains("@RequestMapping(\"/api/admin/vector/brain\")"));
        assertTrue(admin.contains("@PostMapping"));
        assertTrue(graphAdmin.contains("@RequestMapping(\"/api/admin/graph\")"));
        assertTrue(graphAdmin.contains("\"/learn-text\""));
        assertTrue(graphAdmin.contains("\"/learn-file\""));
        assertTrue(graphAdmin.contains("\"/learn-status\""));
        assertTrue(graphAdmin.contains("\"/learn-evidence\""));
        assertTrue(graphAdmin.contains("\"/learn-summary\""));
        assertTrue(graphAdmin.contains("\"/learn-snapshot\""));
        assertTrue(graphAdmin.contains("@PostMapping"));
        assertTrue(graphAdmin.contains("@GetMapping"));
        assertTrue(diagnostics.contains("@RequestMapping(\"/api/diagnostics/rag/brain-state\")"));
        assertTrue(diagnostics.contains("@GetMapping"));
        assertFalse(diagnostics.contains("@PostMapping"));
        assertTrue(vectorSecurity.contains("http.securityMatcher(\"/api/admin/vector/**\")"));
        assertTrue(vectorSecurity.contains(".anyRequest().hasRole(\"VECTOR_ADMIN\")"));
        assertTrue(vectorSecurity.contains("import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;"));
        assertMethodPreambleContains(vectorSecurity,
                "public SecurityFilterChain vectorAdminChain",
                "@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)");
        assertTrue(appSecurity.contains("\"/api/admin/graph\", \"/api/admin/graph/**\""));
        assertTrue(appSecurity.contains(".requestMatchers(\"/api/admin/graph\", \"/api/admin/graph/**\").hasRole(\"ADMIN\")"));
        assertTrue(appSecurity.contains(
                ".ignoringRequestMatchers(adminTokenGuardInterceptor::isHeaderAuthorizedGraphRequest)"));
        assertTrue(adminGuard.contains("\"/api/admin/graph/**\""));
        assertTrue(adminFilter.contains("path.startsWith(\"/api/admin/graph/\")"));
        assertTrue(appSecurity.contains("requestMatchers(HttpMethod.GET, \"/api/diagnostics/**\").hasRole(\"ADMIN\")"));
        assertFalse(appSecurity.contains("\"/api/brain/**\""));
        assertFalse(appSecurity.contains("\"/api/graph-rag/**\""));
    }

    private static void assertMethodPreambleContains(String source, String methodSignature, String expected) {
        int method = source.indexOf(methodSignature);
        assertTrue(method > 0, methodSignature);
        String preamble = source.substring(Math.max(0, method - 240), method);
        assertTrue(preamble.contains(expected), expected);
    }
}
