package com.example.lms.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminTokenGuardFilterTest {
    @Test void durableTaskReadsRequireAdminTokenBeforeMvc() throws Exception {
        AdminTokenGuardFilter filter=filter("admin-secret","",true,false,"local");
        assertDenied(filter,request("GET","/v1/tasks/fixture-id"));
        assertDenied(filter,request("GET","/v1/tasks/fixture-id/result"));
        var authorized=request("GET","/v1/tasks/fixture-id/result");
        authorized.addHeader("X-Admin-Token","admin-secret");
        assertPassedWithAdminRole(filter,authorized);
    }

    @Test
    void deniesSettingsRequestsBeforeMvcForMethodsCaseAndTrailingSlash() throws Exception {
        AdminTokenGuardFilter filter = filter("admin-secret", "", true, false, "local");

        for (String method : new String[]{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}) {
            assertDenied(filter, request(method, "/api/settings/model"));
        }
        assertDenied(filter, request("GET", "/api/settings/model/"));
        assertDenied(filter, request("GET", "/API/SETTINGS/MODEL"));
        assertDenied(filter, request("GET", "/admin/messages"));
        assertDenied(filter, request("POST", "/admin/messages/send"));
        assertDenied(filter, request("POST", "/api/admin/fine-tuning/start"));
        assertDenied(filter, request("GET", "/model-settings"));
        assertDenied(filter, request("GET", "/model-settings/"));
        assertDenied(filter, request("GET", "/api/router"));
        assertDenied(filter, request("GET", "/api/router/status"));
        assertDenied(filter, request("GET", "/API/ROUTER/STATUS"));
        assertDenied(filter, request("GET", "/api/admin/graph"));
        assertDenied(filter, request("GET", "/api/admin/graph/learn-status"));
        assertDenied(filter, request("POST", "/api/admin/graph/learn-text"));
        assertDenied(filter, request("POST", "/api/admin/graph/learn-file"));
        assertDenied(filter, request("GET", "/api/admin/graph/learn-evidence"));
        assertDenied(filter, request("GET", "/api/admin/graph/learn-summary"));
        assertDenied(filter, request("GET", "/api/admin/graph/learn-snapshot"));
        assertDenied(filter, request("GET", "/API/ADMIN/GRAPH/LEARN-STATUS"));
        assertDenied(filter, request("GET", "/internal/agent/tools"));
        assertDenied(filter, request("POST", "/internal/agent/tools/ops.snapshot:invoke"));
        assertDenied(filter, request("GET", "/agent/db-context/snapshot"));
        assertDenied(filter, request("GET", "/internal/soak/quick"));
        assertDenied(filter, request("POST", "/internal/autoevolve/run"));
        assertDenied(filter, request("POST", "/internal/nn/sigmoid-demo"));
        assertDenied(filter, request("POST", "/flows/test:run"));
        assertDenied(filter, request("GET", "/api/internal/autolearn/status"));
        assertDenied(filter, request("POST", "/api/learning/gemini/ingest"));
        assertDenied(filter, request("GET", "/api/integrations/check"));
        assertDenied(filter, request("POST", "/api/diagnostics/langgraph-contamination/replay"));
        assertDenied(filter, request("POST", "/api/diagnostics/embedding/reset"));
        assertDenied(filter, request("GET", "/api/diagnostics/runtime"));
        assertDenied(filter, request("GET", "/api/diagnostics/debug/events/stream"));
        assertDenied(filter, request("POST", "/v1/tasks/ask/async"));
        assertDenied(filter, request("POST", "/api/rag/probe"));
        assertDenied(filter, request("POST", "/api/nova/outbox/sweep"));
        assertDenied(filter, request("POST", "/api/train"));
        assertDenied(filter, request("POST", "/api/train/train-now"));
        assertDenied(filter, request("POST", "/api/translate/train"));
        assertDenied(filter, request("POST", "/api/translate/train-now"));
        assertDenied(filter, request("POST", "/webhooks/channel"));
        assertDenied(filter, request("POST", "/messages/trigger"));
    }

    @Test
    void validAdminOrOwnerTokenPassesToSecurityChain() throws Exception {
        AdminTokenGuardFilter filter = filter("admin-secret", "owner-secret", true, false, "prod");

        MockHttpServletRequest admin = request("POST", "/api/settings/model");
        admin.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        assertPassedWithAdminRole(filter, admin);

        MockHttpServletRequest owner = request("GET", "/model-settings");
        owner.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, owner);

        MockHttpServletRequest router = request("GET", "/api/router/status");
        router.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, router);

        MockHttpServletRequest adminMessages = request("POST", "/admin/messages/send");
        adminMessages.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, adminMessages);

        MockHttpServletRequest fineTuning = request("POST", "/api/admin/fine-tuning/start");
        fineTuning.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, fineTuning);

        MockHttpServletRequest graphStatus = request("GET", "/api/admin/graph/learn-status");
        graphStatus.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, graphStatus);

        MockHttpServletRequest graphLearn = request("POST", "/api/admin/graph/learn-text");
        graphLearn.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, graphLearn);

        MockHttpServletRequest graphFile = request("POST", "/api/admin/graph/learn-file");
        graphFile.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, graphFile);

        MockHttpServletRequest graphEvidence = request("GET", "/api/admin/graph/learn-evidence");
        graphEvidence.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, graphEvidence);

        MockHttpServletRequest graphSummary = request("GET", "/api/admin/graph/learn-summary");
        graphSummary.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, graphSummary);

        MockHttpServletRequest graphSnapshot = request("GET", "/api/admin/graph/learn-snapshot");
        graphSnapshot.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, graphSnapshot);

        MockHttpServletRequest agent = request("GET", "/internal/agent/tools");
        agent.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, agent);

        MockHttpServletRequest dbContext = request("GET", "/agent/db-context/snapshot");
        dbContext.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, dbContext);

        MockHttpServletRequest soak = request("GET", "/internal/soak/quick");
        soak.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, soak);

        MockHttpServletRequest autoevolve = request("POST", "/internal/autoevolve/run");
        autoevolve.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, autoevolve);

        MockHttpServletRequest internalNn = request("POST", "/internal/nn/sigmoid-demo");
        internalNn.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, internalNn);

        MockHttpServletRequest flowRun = request("POST", "/flows/test:run");
        flowRun.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, flowRun);

        MockHttpServletRequest autolearn = request("GET", "/api/internal/autolearn/status");
        autolearn.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, autolearn);

        MockHttpServletRequest geminiLearning = request("POST", "/api/learning/gemini/ingest");
        geminiLearning.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, geminiLearning);

        MockHttpServletRequest integrations = request("GET", "/api/integrations/check");
        integrations.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, integrations);

        MockHttpServletRequest diagnosticsReplay = request("POST", "/api/diagnostics/langgraph-contamination/replay");
        diagnosticsReplay.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, diagnosticsReplay);

        MockHttpServletRequest diagnosticsStream = request("GET", "/api/diagnostics/debug/events/stream");
        diagnosticsStream.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, diagnosticsStream);

        MockHttpServletRequest taskAsync = request("POST", "/v1/tasks/ask/async");
        taskAsync.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, taskAsync);

        MockHttpServletRequest ragProbe = request("POST", "/api/rag/probe");
        ragProbe.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, ragProbe);

        MockHttpServletRequest outboxSweep = request("POST", "/api/nova/outbox/sweep");
        outboxSweep.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, outboxSweep);

        MockHttpServletRequest train = request("POST", "/api/train/train-now");
        train.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, train);

        MockHttpServletRequest translate = request("POST", "/api/translate/train");
        translate.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, translate);

        MockHttpServletRequest channelWebhook = request("POST", "/webhooks/channel");
        channelWebhook.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, channelWebhook);

        MockHttpServletRequest messageTrigger = request("POST", "/messages/trigger");
        messageTrigger.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        assertPassedWithAdminRole(filter, messageTrigger);
    }

    @Test
    void unrelatedPathsBypassSettingsFilter() throws Exception {
        AdminTokenGuardFilter filter = filter("admin-secret", "", true, false, "prod");

        assertPassed(filter, request("GET", "/chat"));
        assertPassed(filter, request("POST", "/internal/dataset/rag"));
        assertPassed(filter, request("POST", "/api/rag/query"));
        assertPassed(filter, request("GET", "/api/nova/outbox/stats"));
        assertPassed(filter, request("GET", "/api/train/123/status"));
    }

    private static void assertDenied(AdminTokenGuardFilter filter, MockHttpServletRequest request) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertFalse(chainCalled.get(), request.getMethod() + " " + request.getRequestURI());
        assertEquals(403, response.getStatus(), request.getMethod() + " " + request.getRequestURI());
        assertTrue(SecurityContextHolder.getContext().getAuthentication() == null
                        || !SecurityContextHolder.getContext().getAuthentication().isAuthenticated(),
                request.getMethod() + " " + request.getRequestURI());
    }

    private static void assertPassed(AdminTokenGuardFilter filter, MockHttpServletRequest request) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertTrue(chainCalled.get(), request.getMethod() + " " + request.getRequestURI());
    }

    private static void assertPassedWithAdminRole(AdminTokenGuardFilter filter,
                                                  MockHttpServletRequest request) throws Exception {
        assertPassed(filter, request);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(auth != null && auth.isAuthenticated(), request.getMethod() + " " + request.getRequestURI());
        assertTrue(auth.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())),
                request.getMethod() + " " + request.getRequestURI());
    }

    private static AdminTokenGuardFilter filter(String adminToken,
                                                String ownerToken,
                                                boolean tokenRequired,
                                                boolean allowQueryToken,
                                                String activeProfiles) {
        AdminTokenGuardInterceptor interceptor = new AdminTokenGuardInterceptor();
        ReflectionTestUtils.setField(interceptor, "expectedToken", adminToken);
        ReflectionTestUtils.setField(interceptor, "ownerToken", ownerToken);
        ReflectionTestUtils.setField(interceptor, "tokenRequired", tokenRequired);
        ReflectionTestUtils.setField(interceptor, "allowQueryToken", allowQueryToken);
        ReflectionTestUtils.setField(interceptor, "activeProfiles", activeProfiles);
        return new AdminTokenGuardFilter(interceptor);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }
}
