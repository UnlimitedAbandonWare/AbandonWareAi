package com.example.lms.api;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Main-mode (interviewDemo=false) session/run ownership contract.
 * Anonymous and ordinary users see and touch only sessions whose ownerKey or
 * administrator.username matches their own identity; the admin read exception
 * stays main-mode-only. Rule SSOT: .windsurf/rules/demo1-session-ownership.md.
 */
class ChatApiControllerMainModeOwnershipTest {

    final ChatHistoryService history = mock(ChatHistoryService.class);
    final ClientOwnerKeyResolver owner = mock(ClientOwnerKeyResolver.class);
    final SettingsService settings = mock(SettingsService.class);
    final ChatRunRegistry runs = new ChatRunRegistry();
    final TestingAuthenticationToken admin =
            new TestingAuthenticationToken("site-admin", null, "ROLE_ADMIN");

    ChatApiController controller(boolean demo) throws Exception {
        ReflectionTestUtils.setField(runs, "replayCapacity", 16);
        ReflectionTestUtils.setField(runs, "ttlSeconds", 60);
        var ctor = ChatApiController.class.getConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        var types = ctor.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (types[i] == ChatHistoryService.class) args[i] = history;
            else if (types[i] == ClientOwnerKeyResolver.class) args[i] = owner;
            else if (types[i] == ChatRunRegistry.class) args[i] = runs;
            else if (types[i] == SettingsService.class) args[i] = settings;
            else if (types[i] == ObjectMapper.class) args[i] = new ObjectMapper();
        }
        var c = (ChatApiController) ctor.newInstance(args);
        if (org.springframework.util.ReflectionUtils.findField(ChatApiController.class, "interviewDemo") != null)
            ReflectionTestUtils.setField(c, "interviewDemo", demo);
        when(settings.getAllSettings()).thenReturn(java.util.Map.of());
        when(owner.ownerKey()).thenReturn("key-A");
        return c;
    }

    ChatSession guestSession(long id, String ownerKey) {
        var s = new ChatSession("synthetic", ownerKey, "ANON"); s.setId(id); return s;
    }

    ChatSession accountSession(long id, String username) {
        var s = new ChatSession("synthetic", new Administrator(username, null, username));
        s.setId(id);
        return s;
    }

    UserDetails principal(String username, String... authorities) {
        var p = mock(UserDetails.class);
        when(p.getUsername()).thenReturn(username);
        doReturn(java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new).toList()).when(p).getAuthorities();
        return p;
    }

    MockHttpServletRequest loopback() {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        return req;
    }

    @Test
    void anonymousA_cannotListAnonymousB_sessions() throws Exception {
        var c = controller(false);
        when(history.getSessionsForUser(anyString(), anyString(), eq(50)))
                .thenReturn(List.of(guestSession(8, "key-A"), guestSession(7, "key-B")));
        assertEquals(List.of(8L),
                c.sessions(null, 50, loopback()).stream().map(info -> info.id()).toList());
    }

    @Test
    void anonymousA_cannotReadStateOrCancel_ofAnonymousB() throws Exception {
        var c = controller(false);
        when(history.getSessionWithMessages(7L)).thenReturn(guestSession(7, "key-B"));
        var run = runs.beginOrJoin(7L);
        var token = run.context().clientToken();
        assertEquals(false, c.state(7L, false, token, null).getBody().get("running"));
        assertEquals(false, c.cancel(7L, null, token, null).getBody().get("cancelled"));
        assertTrue(runs.isRunning(7L));
    }

    @Test
    void anonymousA_cannotStreamInto_sessionOfAnonymousB() throws Exception {
        var c = controller(false);
        when(history.getSessionWithMessages(7L, 1)).thenReturn(guestSession(7, "key-B"));
        var req = loopback();
        req.addHeader("X-Chat-Run-Token", "00000000-0000-0000-0000-0000000000ff");
        var dto = ChatRequestDto.builder().sessionId(7L).message("x").build();
        var events = c.chatStream(dto, true, false, null, req).collectList().block();
        assertNotNull(events);
        assertEquals("error", events.get(0).data().type());
    }

    @Test
    void anonymous_withoutOwnerCookie_ipuaFallback_doesNotSeeOthers() throws Exception {
        var c = controller(false);
        when(owner.ownerKey()).thenReturn("ipua:9f86d081884c7d65");
        when(history.getSessionsForUser(anyString(), anyString(), eq(50)))
                .thenReturn(List.of(guestSession(7, "ipua:9f86d081884c7d65")));
        assertTrue(c.sessions(null, 50, loopback()).isEmpty(),
                "ipua fallback identity must not list sessions: shared IP+UA makes it a group key");
    }

    @Test
    void userA_cannotAccess_userB_sessions() throws Exception {
        var c = controller(false);
        var auth = new TestingAuthenticationToken("userA", null, "ROLE_USER");
        var foreign = accountSession(7, "userB");
        when(history.getSessionWithMessages(7L)).thenReturn(foreign);
        var run = runs.beginOrJoin(7L);
        var token = run.context().clientToken();
        when(history.getSessionsForUser(eq("userA"), anyString(), eq(50)))
                .thenReturn(List.of(foreign));
        assertTrue(c.sessions(principal("userA", "ROLE_USER"), 50, loopback()).isEmpty());
        assertEquals(false, c.state(7L, false, token, auth).getBody().get("running"));
        assertEquals(HttpStatus.FORBIDDEN, c.getSession(7L, false, auth).getStatusCode());
    }

    @Test
    void owner_canStillListAndResume_ownSession() throws Exception {
        var c = controller(false);
        var own = guestSession(8, "key-A");
        when(history.getSessionWithMessages(8L)).thenReturn(own);
        when(history.getSessionsForUser(anyString(), anyString(), eq(50)))
                .thenReturn(List.of(own, guestSession(7, "key-B")));
        assertEquals(List.of(8L),
                c.sessions(null, 50, loopback()).stream().map(info -> info.id()).toList());
        var run = runs.beginOrJoin(8L);
        var token = run.context().clientToken();
        var state = c.state(8L, false, token, null).getBody();
        assertEquals(true, state.get("running"));
        assertEquals(HttpStatus.OK, c.getSession(8L, false, null).getStatusCode());
    }

    @Test
    void admin_mainMode_canListAndReadOthers_keepsAdminException() throws Exception {
        var c = controller(false);
        var foreign = guestSession(7, "key-B");
        var own = guestSession(8, "key-A");
        when(history.getSessionWithMessages(7L)).thenReturn(foreign);
        when(history.getSessionWithMessages(8L)).thenReturn(own);
        when(history.getAllSessionsForAdmin(50)).thenReturn(List.of(own, foreign));
        var listed = c.sessions(principal("site-admin", "ROLE_ADMIN"), 50, loopback())
                .stream().map(info -> info.id()).toList();
        assertTrue(listed.contains(7L), "main-mode admin keeps the all-session list");
        assertEquals(HttpStatus.OK, c.getSession(7L, false, admin).getStatusCode(),
                "main-mode admin keeps foreign session read");
        var run = runs.beginOrJoin(7L);
        var token = run.context().clientToken();
        assertEquals(true, c.state(7L, false, token, admin).getBody().get("running"),
                "main-mode admin keeps foreign run state");
    }

    @Test
    void admin_interviewMode_cannotReadOthers() throws Exception {
        var c = controller(true);
        when(history.getSessionWithMessages(7L)).thenReturn(guestSession(7, "key-B"));
        var run = runs.beginOrJoin(7L);
        var token = run.context().clientToken();
        assertEquals(false, c.state(7L, false, token, admin).getBody().get("running"));
        assertEquals(false, c.cancel(7L, null, token, admin).getBody().get("cancelled"));
        assertEquals(HttpStatus.FORBIDDEN, c.getSession(7L, false, admin).getStatusCode());
        assertTrue(runs.isRunning(7L));
    }
}
