package com.example.lms.security;

import com.example.lms.assist.InterviewDemoPublicAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InterviewDemoPublicAddressTest {
    private static final String ORIGIN = "https://abandonwareai.kro.kr";
    @TempDir Path temporary;

    private InterviewDemoPublicAddress address(String fixed) {
        var address = new InterviewDemoPublicAddress();
        ReflectionTestUtils.setField(address, "fixedPublicOrigin", fixed);
        ReflectionTestUtils.setField(address, "stateFile", temporary.resolve("absent.json").toString());
        return address;
    }

    private int request(InterviewDemoPublicAddress address, String origin, String host, String remote) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/chat/sync");
        request.setScheme("http");
        request.setServerName(host);
        request.setServerPort(18180);
        request.setRemoteAddr(remote);
        if (origin != null) request.addHeader("Origin", origin);
        var response = new MockHttpServletResponse();
        new ChatOpenSecurityConfig.InterviewDemoFilter(address::currentOrigin)
                .doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        return response.getStatus();
    }

    @Test void fixedHttpsOriginAcceptsTheApprovedLoopbackProxy() throws Exception {
        var address = address(ORIGIN);
        assertEquals(ORIGIN, address.currentOrigin());
        assertEquals(204, request(address, ORIGIN, "abandonwareai.kro.kr", "127.0.0.1"));
    }

    @Test void defaultDoesNotTrustTheStableOrigin() throws Exception {
        assertEquals("", address("").currentOrigin());
        assertEquals(403, request(address(""), ORIGIN, "abandonwareai.kro.kr", "127.0.0.1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://abandonwareai.kro.kr", "https://user@abandonwareai.kro.kr",
            "https://abandonwareai.kro.kr/path", "https://abandonwareai.kro.kr?x=1",
            "https://abandonwareai.kro.kr#x", "https://abandonwareai.kro.kr:18180", "not-a-url"})
    void malformedConfiguredOriginFailsClosed(String configured) {
        assertEquals("", address(configured).currentOrigin());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://abandonwareai.kro.kr.evil.example", "http://abandonwareai.kro.kr",
            "https://abandonwareai.kro.kr/path", "https://abandonwareai.kro.kr:18180"})
    void foreignOriginIsRejected(String origin) throws Exception {
        assertEquals(403, request(address(ORIGIN), origin, "abandonwareai.kro.kr", "127.0.0.1"));
    }

    @Test void fixedOriginDoesNotRelaxProxyIdentityOrHost() throws Exception {
        assertEquals(403, request(address(ORIGIN), ORIGIN, "other.example", "127.0.0.1"));
        assertEquals(403, request(address(ORIGIN), ORIGIN, "abandonwareai.kro.kr", "203.0.113.10"));
        assertEquals(403, request(address(ORIGIN), ORIGIN, "abandonwareai.kro.kr", "192.168.1.20"));
    }

    @Test void nativeLocalValidationAndRouteRestrictionsArePreserved() throws Exception {
        assertEquals(204, request(address(ORIGIN), null, "localhost", "127.0.0.1"));
        var request = new MockHttpServletRequest("GET", "/conversate");
        request.setRemoteAddr("127.0.0.1");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new ChatOpenSecurityConfig.InterviewDemoFilter(address(ORIGIN)::currentOrigin)
                .doFilter(request, response, (req, res) -> called.set(true));
        assertEquals(404, response.getStatus());
        assertFalse(called.get());
    }

    @Test void emptySettingRetainsTheExistingLiveTunnelReceipt() throws Exception {
        var current = ProcessHandle.current();
        var receipt = temporary.resolve("state.json");
        Files.writeString(receipt, "{\"status\":\"active\",\"expiresAt\":" + (System.currentTimeMillis() + 60000)
                + ",\"pid\":" + current.pid() + ",\"startedAt\":\"" + current.info().startInstant().orElseThrow()
                + "\",\"publicUrl\":\"https://synthetic-test.trycloudflare.com\"}");
        var address = address("");
        ReflectionTestUtils.setField(address, "stateFile", receipt.toString());
        assertEquals("https://synthetic-test.trycloudflare.com", address.currentOrigin());
        Files.delete(receipt);
        assertEquals("", address.currentOrigin());
    }
}
