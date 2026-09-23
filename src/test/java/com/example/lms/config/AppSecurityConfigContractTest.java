package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSecurityConfigContractTest {

    private static final Path SOURCE = Path.of("main/java/com/example/lms/config/AppSecurityConfig.java");

    @Test
    void noCatchAllPermitAllChainRemains() throws Exception {
        String source = Files.readString(SOURCE);

        assertEquals(1, occurrences(source, "anyRequest().permitAll()"));
        assertTrue(source.contains("http.securityMatcher(\"/api/probe/**\", \"/internal/probe/**\")"));
        assertFalse(source.contains("LOWEST_PRECEDENCE"));
        assertEquals(1, occurrences(source, ".securityMatcher(\"/**\")"));
        assertTrue(source.contains(".loginPage(\"/login\")"));
        assertTrue(source.contains(".loginProcessingUrl(\"/login\")"));
        assertTrue(source.contains(".defaultSuccessUrl(\"/index\", true)"));
        assertTrue(source.contains(".failureUrl(\"/login?error\")"));
        assertTrue(source.contains(".userDetailsService(adminDetailsService::loadUserByUsername)"));
        assertTrue(source.contains(".logoutSuccessUrl(\"/login?logout\")"));
        assertFalse(source.contains(".formLogin(form -> form.disable())"));
        assertFalse(source.contains(".rememberMe(rem -> rem.disable())"));
        assertFalse(source.contains(".logout(logout -> logout.disable())"));
        assertTrue(source.contains(".addFilterBefore(adminTokenGuardFilter, UsernamePasswordAuthenticationFilter.class)"));
    }

    @Test
    void settingsAdminRulePrecedesPublicMatchers() throws Exception {
        String source = Files.readString(SOURCE);

        int settingsRule = source.indexOf("\"/api/settings\"");
        int routerRule = source.indexOf("\"/api/router\"");
        int diagnosticsGet = source.indexOf("\"/api/diagnostics/**\"");
        int datasetPost = source.indexOf("requestMatchers(HttpMethod.POST, \"/internal/dataset/**\").permitAll()");
        int publicChat = source.indexOf("\"/api/chat/**\"");
        int authenticatedFallback = source.indexOf(".anyRequest().authenticated()");

        assertTrue(settingsRule > 0);
        assertTrue(routerRule > settingsRule);
        assertTrue(diagnosticsGet > routerRule);
        assertTrue(datasetPost > diagnosticsGet);
        assertTrue(publicChat > datasetPost);
        assertTrue(publicChat > settingsRule);
        assertTrue(authenticatedFallback > publicChat);
        assertTrue(source.contains(").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/router\", \"/api/router/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/internal/soak\", \"/internal/soak/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/internal/autoevolve\", \"/internal/autoevolve/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/internal/nn\", \"/internal/nn/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/flows\", \"/flows/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/admin\", \"/admin/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/admin/fine-tuning\", \"/api/admin/fine-tuning/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/internal/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/learning/gemini\", \"/api/learning/gemini/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/integrations/check\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/v1/tasks\", \"/v1/tasks/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/rag/probe\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/nova/outbox/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/train\", \"/api/train/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/translate/train\", \"/api/translate/train-now\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/webhooks/channel\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/messages/trigger\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/diagnostics/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.GET, \"/api/diagnostics/**\").hasRole(\"ADMIN\")"));
        assertFalse(source.contains("requestMatchers(HttpMethod.GET, \"/api/diagnostics/**\").permitAll()"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/internal/dataset/**\").permitAll()"));
    }

    @Test
    void triadicAdjudicationReadAndRunStayAdminOnly() throws Exception {
        String source = Files.readString(SOURCE);

        int triadicAdmin = source.indexOf(
                "requestMatchers(\"/api/diagnostics/debug/triadic-adjudication\").hasRole(\"ADMIN\")");
        int broadDiagnosticsGet = source.indexOf(
                "requestMatchers(HttpMethod.GET, \"/api/diagnostics/**\").hasRole(\"ADMIN\")");

        assertTrue(triadicAdmin > 0);
        assertTrue(triadicAdmin < broadDiagnosticsGet);
    }

    @Test
    void harmonyDashboardAndScoreApiArePublicReadOnlyObservabilitySurfaces() throws Exception {
        String source = Files.readString(SOURCE);

        int harmonyApi = source.indexOf("\"/api/harmony/**\"");
        int faithfulnessMetric = source.indexOf("\"/api/metrics/faithfulness\"");
        int harmonyPage = source.indexOf("\"/harmony\"");
        int authenticatedFallback = source.indexOf(".anyRequest().authenticated()");

        assertTrue(harmonyApi > 0);
        assertTrue(faithfulnessMetric > harmonyApi);
        assertTrue(harmonyPage > faithfulnessMetric);
        assertTrue(authenticatedFallback > harmonyPage);
        assertFalse(source.contains(".requestMatchers(\"/api/**\").permitAll()"));
    }

    @Test
    void harmonyPostRoutesAreNotCsrfIgnoredBecauseTheSurfaceIsReadOnly() throws Exception {
        String source = Files.readString(SOURCE);
        int csrfStart = source.lastIndexOf(".csrf(csrf -> csrf");
        int csrfEnd = source.indexOf(".addFilterBefore", csrfStart);
        String csrfBlock = source.substring(csrfStart, csrfEnd);

        assertFalse(csrfBlock.contains("\"/api/harmony/**\""));
        assertFalse(csrfBlock.contains("\"/harmony\""));
    }

    @Test
    void internalDatasetAndAgentReportPostsAreCsrfIgnoredButNotCatchAllPublicAuth() throws Exception {
        String source = Files.readString(SOURCE);
        int csrfStart = source.indexOf(".csrf(csrf -> csrf");
        int csrfEnd = source.indexOf(".addFilterBefore", csrfStart);
        String csrfBlock = source.substring(csrfStart, csrfEnd);

        assertTrue(csrfBlock.contains("\"/internal/dataset/**\""));
        assertTrue(csrfBlock.contains("\"/api/agent/report/**\""));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/internal/dataset/**\").permitAll()"));
        assertFalse(source.contains(".requestMatchers(\"/internal/**\").permitAll()"));
        assertFalse(source.contains(".requestMatchers(\"/**\").permitAll()"));
    }

    @Test
    void graphCsrfExemptionRequiresValidatedHeaderInDefaultChain() throws Exception {
        String source = Files.readString(SOURCE);
        int csrfStart = source.lastIndexOf(".csrf(csrf -> csrf");
        int csrfEnd = source.indexOf(".addFilterBefore", csrfStart);
        String csrfBlock = source.substring(csrfStart, csrfEnd);

        assertTrue(csrfBlock.contains("adminTokenGuardInterceptor::isHeaderAuthorizedGraphRequest"));
        assertFalse(csrfBlock.contains("\"/api/admin/graph/**\""));
    }

    @Test
    void probeEndpointsReachControllerTokenGateWithoutFrameworkCsrfBlock() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("probeSecurityFilterChain(HttpSecurity http)"));
        assertTrue(source.contains("http.securityMatcher(\"/api/probe/**\", \"/internal/probe/**\")"));
        assertTrue(source.contains(".csrf(csrf -> csrf.disable())"));
        assertTrue(source.contains(".anyRequest().permitAll()"));
        String probeController = Files.readString(Path.of("main/java/com/example/lms/probe/SearchProbeController.java"));
        assertTrue(probeController.contains("@Value(\"${probe.admin-token:}\") String adminToken"));
        assertTrue(probeController.contains("@RequestHeader(value = \"X-Probe-Token\""));
        assertFalse(source.contains(".requestMatchers(\"/api/**\").permitAll()"));
    }

    @Test
    void probeChainHonorsForceHttpsWithoutBreakingControllerTokenGate() throws Exception {
        String source = Files.readString(SOURCE);
        int start = source.indexOf("public SecurityFilterChain probeSecurityFilterChain");
        int end = source.indexOf("public SecurityFilterChain defaultSecurityFilterChain", start);
        String probe = source.substring(start, end);

        assertTrue(probe.contains("if (forceHttps)"));
        assertTrue(probe.contains("http.portMapper(mapper -> mapper.http(httpPort).mapsTo(httpsPort))"));
        assertTrue(probe.contains("http.requiresChannel(channel -> channel.anyRequest().requiresSecure())"));
        assertTrue(probe.contains(".anyRequest().permitAll()"));
        assertTrue(probe.contains("SessionCreationPolicy.STATELESS"));
    }

    @Test
    void servletSecurityFilterChainsAreServletOnly() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;"));
        assertMethodPreambleContains(source,
                "public SecurityFilterChain probeSecurityFilterChain",
                "@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)");
        assertMethodPreambleContains(source,
                "public SecurityFilterChain defaultSecurityFilterChain",
                "@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)");
    }

    @Test
    void adminAuthenticationProviderIsChainLocalNotGlobalBean() throws Exception {
        String source = Files.readString(SOURCE);

        assertFalse(source.contains("@Bean\n    public DaoAuthenticationProvider adminAuthProvider"));
        assertTrue(source.contains("private DaoAuthenticationProvider adminAuthProvider("));
        assertTrue(source.contains("AdminDetailsServiceImpl adminDetailsService"));
        assertTrue(source.contains("PasswordEncoder passwordEncoder"));
        assertTrue(source.contains(".authenticationProvider(adminAuthProvider(adminDetailsService::loadUserByUsername, passwordEncoder))"));
    }

    @Test
    void adminDetailsServiceDoesNotPublishSecondGlobalUserDetailsService() throws Exception {
        String source = Files.readString(SOURCE);
        String adminDetailsService = Files.readString(Path.of("main/java/com/example/lms/service/AdminDetailsServiceImpl.java"));

        assertFalse(adminDetailsService.contains("implements UserDetailsService"));
        assertTrue(adminDetailsService.contains("public UserDetails loadUserByUsername(String username)"));
        assertTrue(source.contains(".authenticationProvider(adminAuthProvider(adminDetailsService::loadUserByUsername, passwordEncoder))"));
        assertTrue(source.contains(".userDetailsService(adminDetailsService::loadUserByUsername)"));
        assertTrue(source.contains(".key(effectiveRememberMeKey())"));
        assertTrue(source.contains(".alwaysRemember(true)"));
    }

    @Test
    void adminTokenMvcGuardCoversInternalPaths() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/AdminTokenGuardWebMvcConfig.java"));

        assertTrue(source.contains("\"/internal/**\""));
        assertTrue(source.contains("\"/internal/nn/**\""));
        assertTrue(source.contains("\"/flows/**\""));
        assertTrue(source.contains("\"/messages/trigger\""));
        assertTrue(source.contains("\"/admin/**\""));
        assertTrue(source.contains("\"/api/admin/fine-tuning/**\""));
        assertTrue(source.contains("\"/api/internal/**\""));
        assertTrue(source.contains("\"/api/learning/gemini/**\""));
        assertTrue(source.contains("\"/api/diagnostics/**\""));
        assertTrue(source.contains("\"/agent/db-context\""));
        assertTrue(source.contains("\"/agent/db-context/**\""));
        assertTrue(source.contains("\"/api/router\""));
        assertTrue(source.contains("\"/api/router/**\""));
        assertTrue(source.contains("\"/api/integrations/check\""));
    }

    @Test
    void publicOperationalWriteRoutesRequireAdminRoleBeforeBroadPublicMatchers() throws Exception {
        String source = Files.readString(SOURCE);

        int tasksAdmin = source.indexOf("requestMatchers(\"/v1/tasks\", \"/v1/tasks/**\").hasRole(\"ADMIN\")");
        int ragProbeAdmin = source.indexOf("requestMatchers(HttpMethod.POST, \"/api/rag/probe\").hasRole(\"ADMIN\")");
        int outboxAdmin = source.indexOf("requestMatchers(HttpMethod.POST, \"/api/nova/outbox/**\").hasRole(\"ADMIN\")");
        int trainAdmin = source.indexOf("requestMatchers(HttpMethod.POST, \"/api/train\", \"/api/train/**\").hasRole(\"ADMIN\")");
        int translateAdmin = source.indexOf("requestMatchers(HttpMethod.POST, \"/api/translate/train\", \"/api/translate/train-now\").hasRole(\"ADMIN\")");
        int ragPublic = source.indexOf("\"/api/rag/**\"");
        int chatPublic = source.indexOf("\"/api/chat/**\"");
        int authenticatedFallback = source.indexOf(".anyRequest().authenticated()");

        assertTrue(tasksAdmin > 0);
        assertTrue(ragProbeAdmin > tasksAdmin);
        assertTrue(outboxAdmin > ragProbeAdmin);
        assertTrue(trainAdmin > outboxAdmin);
        assertTrue(translateAdmin > trainAdmin);
        assertTrue(ragProbeAdmin < ragPublic);
        assertTrue(outboxAdmin < authenticatedFallback);
        assertTrue(translateAdmin < authenticatedFallback);
        assertTrue(chatPublic > outboxAdmin);
    }

    @Test
    void applicationYamlDerivesForceHttpsFromServerSslEnabledUnlessOverridden() throws Exception {
        String yaml = Files.readString(Path.of("main/resources/application.yml"));

        assertTrue(yaml.contains("force-https: ${SECURITY_FORCE_HTTPS:${SERVER_SSL_ENABLED:false}}"));
    }

    @Test
    void servletSecurityChainsRequireSecureChannelWhenForceHttpsIsEnabled() throws Exception {
        String appSecurity = Files.readString(SOURCE);
        String chatOpenSecurity = Files.readString(
                Path.of("main/java/com/example/lms/security/ChatOpenSecurityConfig.java"));

        assertTrue(appSecurity.contains("@Value(\"${security.force-https:false}\")"));
        assertTrue(appSecurity.contains(".requiresChannel(channel -> channel.anyRequest().requiresSecure())"));
        assertTrue(chatOpenSecurity.contains("@Value(\"${security.force-https:false}\")"));
        assertTrue(chatOpenSecurity.contains(".requiresChannel(channel -> channel.anyRequest().requiresSecure())"));
    }

    @Test
    void httpsOffloadAndHttpSmokeUseForwardedProtoAndRedirectClassification() throws Exception {
        String startScript = Files.readString(Path.of("scripts/domain_public_https_start.ps1"));
        String identity = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/identity/IdentityInterceptor.java"));
        String ownerKey = Files.readString(
                Path.of("main/java/com/example/lms/web/OwnerKeyBootstrapFilter.java"));

        assertTrue(startScript.contains("--security.force-https=true"));
        assertTrue(startScript.contains("X-Forwarded-Proto: https"));
        assertTrue(startScript.contains("$httpRedirectCode = $httpCode -in @(\"301\", \"302\", \"307\", \"308\")"));
        assertTrue(startScript.contains("$httpRedirectOk = Test-HttpsRedirectTarget -Location $httpRedirectUrl -ExpectedHost $Domain -ExpectedPort $HttpsPort"));
        assertTrue(startScript.contains("$target.Scheme -ieq \"https\""));
        assertTrue(startScript.contains("$actualHost -ieq $wantedHost"));
        assertFalse(startScript.contains(".StartsWith(\"https://$Domain\")"));
        assertTrue(startScript.contains("$httpOk = $httpRedirectCode -and $httpRedirectOk"));
        assertFalse(startScript.contains("$httpCode -eq \"200\" -or"));
        assertTrue(startScript.contains("ready=local-only publicEdgeVerified=false"));
        assertTrue(startScript.contains("ready=true publicEdgeVerified=true"));
        assertTrue(startScript.contains(
                "classification=public-https-response evidence_needed=trusted-public-domain-response"));
        assertTrue(startScript.contains(
                "tlsMode=$TlsMode appScheme=$healthScheme httpCode=$httpCode httpsCode=$httpsCode httpRedirectOk=$httpRedirectOk"));
        int publicProbeStart = startScript.indexOf("[AWX][domain-start] publicProbe http=");
        int publicProbeEnd = startScript.indexOf(
                "[AWX][domain-start] publicProbe.cleanup=stopping", publicProbeStart);
        assertTrue(publicProbeStart >= 0);
        assertTrue(publicProbeEnd > publicProbeStart);
        String publicProbeBranch = startScript.substring(publicProbeStart, publicProbeEnd);
        assertFalse(publicProbeBranch.contains("classification=provider-disabled"));
        assertFalse(identity.contains("getHeader(\"X-Forwarded-Proto\")"));
        assertFalse(identity.contains("getHeader(\"Forwarded\")"));
        assertFalse(ownerKey.contains("getHeader(\"X-Forwarded-Proto\")"));
        assertFalse(ownerKey.contains("getHeader(\"Forwarded\")"));
    }

    @Test
    void effectiveLogoutChainRevokesAdminSessionCapability() throws Exception {
        String appSecurity = Files.readString(SOURCE);

        assertTrue(appSecurity.contains("revokePresentedSession(request, response)"));
    }

    @Test
    void embeddedTlsScriptsTreatDistinctKeyPasswordAsOptional() throws Exception {
        String start = Files.readString(Path.of("scripts/domain_public_https_start.ps1"));
        String preflight = Files.readString(Path.of("scripts/domain_public_https_preflight.ps1"));

        assertTrue(start.contains("keyPasswordOptional=true"));
        assertFalse(start.contains(
                "evidence_needed=SERVER_SSL_ENABLED,SERVER_SSL_KEY_STORE,SERVER_SSL_KEY_STORE_PASSWORD,SERVER_SSL_KEY_PASSWORD"));
        assertTrue(preflight.contains("env.SERVER_SSL_KEY_PASSWORD.optional"));
        assertTrue(preflight.contains("Get-DistinctPasswordKeyStoreCertificate"));
        assertTrue(preflight.contains("KeyStore keyStore = KeyStore.getInstance"));
        assertTrue(preflight.contains("keyStore.load(input, storePassword)"));
        assertTrue(preflight.contains("keyStore.getKey(alias, keyPassword)"));
        assertTrue(preflight.contains("env(\"SERVER_SSL_KEY_STORE_PASSWORD\")"));
        assertTrue(preflight.contains("env(\"SERVER_SSL_KEY_PASSWORD\")"));
        assertFalse(preflight.contains("& java $probeSource $keyStorePassword"));
        assertFalse(preflight.contains("& java $probeSource $keyPassword"));
    }

    @Test
    void publicLauncherActivatesProductionGuardForEachTlsMode() throws Exception {
        String start = Files.readString(Path.of("scripts/domain_public_https_start.ps1"));

        assertTrue(start.contains("--spring.profiles.active=prod"));
        assertFalse(start.contains("--spring.profiles.active=local"));
        assertTrue(start.contains(
                "$forwardHeadersStrategy = if ($embeddedSslRequired) { \"none\" } else { \"framework\" }"));
        assertTrue(start.contains("--server.forward-headers-strategy=$forwardHeadersStrategy"));
        assertTrue(start.contains("$args += \"--server.address=127.0.0.1\""));
        assertTrue(start.contains("$args += \"--security.tls-offload.enabled=true\""));
        assertTrue(start.contains("$args += \"--security.tls-offload.enabled=false\""));
    }

    @Test
    void gatewaySecurityTestTaskRunsOnlyGatewayBoundaryContracts() throws Exception {
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(build.contains("tasks.register<Test>(\"gatewaySecurityTest\")"));
        assertTrue(build.contains("LocalLlmGatewayHeadersTest.java"));
        assertTrue(build.contains("OpenCodeFreeQuotaGuardTest.java"));
        assertTrue(build.contains("LlmRouterGatewaySecurityTest.java"));
        assertTrue(build.contains("AppSecurityConfigContractTest.java"));
        assertTrue(build.contains("gatewaySecurityTest.output.classesDirs"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void assertMethodPreambleContains(String source, String methodSignature, String expected) {
        int method = source.indexOf(methodSignature);
        assertTrue(method > 0, methodSignature);
        String preamble = source.substring(Math.max(0, method - 240), method);
        assertTrue(preamble.contains(expected), expected);
    }
}
