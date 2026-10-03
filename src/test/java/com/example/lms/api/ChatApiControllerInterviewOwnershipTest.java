package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatApiControllerInterviewOwnershipTest {
    final ChatHistoryService history = mock(ChatHistoryService.class);
    final ClientOwnerKeyResolver owner = mock(ClientOwnerKeyResolver.class);
    final ChatRunRegistry runs = new ChatRunRegistry();
    final TestingAuthenticationToken admin = new TestingAuthenticationToken("prototype", null, "ROLE_ADMIN");
    ChatApiController controller(boolean demo) throws Exception {
        ReflectionTestUtils.setField(runs, "replayCapacity", 16);
        ReflectionTestUtils.setField(runs, "ttlSeconds", 60);
        var ctor = ChatApiController.class.getConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        var types = ctor.getParameterTypes();
        for (int i=0; i<args.length; i++) {
            if (types[i] == ChatHistoryService.class) args[i]=history;
            else if (types[i] == ClientOwnerKeyResolver.class) args[i]=owner;
            else if (types[i] == ChatRunRegistry.class) args[i]=runs;
        }
        var c = (ChatApiController) ctor.newInstance(args);
        if (org.springframework.util.ReflectionUtils.findField(ChatApiController.class, "interviewDemo") != null)
            ReflectionTestUtils.setField(c, "interviewDemo", demo);
        when(owner.ownerKey()).thenReturn("client-A");
        return c;
    }
    ChatSession session(long id, String key) {
        var s = new ChatSession("synthetic", key, "ANON"); s.setId(id); return s;
    }
    static java.util.stream.Stream<Arguments> modesAndCallers() {
        return java.util.stream.Stream.of(
                Arguments.of(false, false), Arguments.of(false, true),
                Arguments.of(true, false), Arguments.of(true, true));
    }
    @ParameterizedTest(name="demo={0}, prototypeAdmin={1}")
    @MethodSource("modesAndCallers")
    void foreignRunAndSessionAreDeniedInMainAndInterviewModes(boolean demo, boolean prototypeAdmin) throws Exception {
        var c=controller(demo); var foreign=session(7,"client-B");
        var authentication=prototypeAdmin ? admin : null;
        when(history.getSessionWithMessages(7L)).thenReturn(foreign);
        var run=runs.beginOrJoin(7L); var token=run.context().clientToken();
        assertEquals(false,c.state(7L,false,token,authentication).getBody().get("running"));
        assertEquals(false,c.cancel(7L,null,token,authentication).getBody().get("cancelled"));
        assertEquals(false,c.acknowledgeRun(7L,null,token,authentication).getBody().get("acknowledged"));
        assertTrue(runs.isRunning(7L));
        assertEquals(HttpStatus.FORBIDDEN,c.getSession(7L,false,authentication).getStatusCode());
    }
    @ParameterizedTest(name="demo={0}, prototypeAdmin={1}")
    @MethodSource("modesAndCallers")
    void ownRunStateAcknowledgementAndCancellationRemainUsable(boolean demo, boolean prototypeAdmin) throws Exception {
        var c=controller(demo); when(history.getSessionWithMessages(8L)).thenReturn(session(8,"client-A"));
        var authentication=prototypeAdmin ? admin : null;
        var run=runs.beginOrJoin(8L); var token=run.context().clientToken();
        assertEquals(true,c.state(8L,false,token,authentication).getBody().get("running"));
        assertEquals(true,c.acknowledgeRun(8L,null,token,authentication).getBody().get("acknowledged"));
        assertEquals(true,c.cancel(8L,null,token,authentication).getBody().get("cancelled"));
        assertFalse(runs.isRunning(8L));
    }
    @ParameterizedTest(name="demo={0}, prototypeAdmin={1}")
    @MethodSource("modesAndCallers")
    void sessionListCannotUseAdministratorWideProjection(boolean demo, boolean prototypeAdmin) throws Exception {
        var c=controller(demo); UserDetails principal=prototypeAdmin ? mock(UserDetails.class) : null;
        if (principal != null) {
            when(principal.getUsername()).thenReturn("prototype");
            doReturn(admin.getAuthorities()).when(principal).getAuthorities();
        }
        when(history.getSessionsForUser(anyString(),anyString(),eq(50))).thenReturn(List.of(session(8,"client-A"),session(7,"client-B")));
        when(history.getAllSessionsForAdmin(50)).thenReturn(List.of(session(8,"client-A"),session(7,"client-B")));
        var req=new MockHttpServletRequest(); req.setRemoteAddr("127.0.0.1");
        assertEquals(List.of(8L),c.sessions(principal,50,req).stream().map(info -> info.id()).toList());
        verify(history,never()).getAllSessionsForAdmin(anyInt());
    }
}
