package com.example.lms.security;

import com.example.lms.assist.ConversateController;
import com.example.lms.assist.ConversateSessionService;
import com.example.lms.web.PageController;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.service.ModelSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterviewDemoFocusedTest {
    @Test void registeredTunnelOriginWorksOnlyThroughLoopbackWithMatchingHost() throws Exception {
        for(boolean match:new boolean[]{true,false}){
            var request=new MockHttpServletRequest("POST","/api/assist/sessions");request.setRemoteAddr("127.0.0.1");request.setServerName("demo.trycloudflare.com");request.setScheme("http");request.setServerPort(18080);
            request.addHeader("Origin",match?"https://demo.trycloudflare.com":"https://other.trycloudflare.com");
            var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
            new ChatOpenSecurityConfig.InterviewDemoFilter(()->"https://demo.trycloudflare.com").doFilter(request,response,(r,s)->reached.set(true));
            assertEquals(match,reached.get());if(!match)assertEquals(403,response.getStatus());
        }
        assertTrue(request("GET","/api/assist/sessions/12345678-1234-1234-1234-123456789012/output/poll","127.0.0.1",null).reached());
    }
    private record Probe(boolean reached, int status) {}
    private Probe request(String method,String path,String remote,String origin) throws Exception {
        var request=new MockHttpServletRequest(method,path);
        request.setRemoteAddr(remote);request.setServerName("192.168.1.4");request.setServerPort(18080);request.setScheme("http");
        if(origin!=null)request.addHeader("Origin",origin);
        var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
        new ChatOpenSecurityConfig.InterviewDemoFilter().doFilter(request,response,(r,s)->reached.set(true));
        assertEquals("no-store",response.getHeader("Cache-Control"));
        return new Probe(reached.get(),response.getStatus());
    }
    @Test void localAnonymousSurfaceHasOnlyTheTwoDemoCapabilities() throws Exception {
        for(String path:new String[]{"/","/chat","/chat-ui","/index.html","/assets/interview/index.html","/assets/display/receiver.js","/api/assist/bootstrap"})
            assertTrue(request("GET",path,"192.168.1.5",null).reached(),path);
        assertTrue(request("POST","/api/chat/sync","127.0.0.1","http://192.168.1.4:18080").reached());
        assertTrue(request("POST","/api/assist/sessions","127.0.0.1",null).reached());
    }
    @Test void removedRoutesReturnNotFoundWithoutRedirectOrAuthentication() throws Exception {
        for(String path:new String[]{"/login","/register","/signup","/admin/dashboard","/model-settings","/api/admin/models","/api/assist/materials","/conversate","/assets/display/../private.html","/api/chat/stream"}) {
            var result=request("GET",path,"127.0.0.1",null);assertFalse(result.reached(),path);assertEquals(404,result.status(),path);
        }
    }
    @Test void crossOriginOrNonLocalRequestsCannotUseAnonymousDemo() throws Exception {
        for(String origin:new String[]{"null","https://192.168.1.4:18080","http://192.168.1.4:18081","http://evil.example","http://192.168.1.4:18080/path"}){
            var result=request("POST","/api/assist/sessions","127.0.0.1",origin);assertFalse(result.reached(),origin);assertEquals(403,result.status());
        }
        assertEquals(403,request("GET","/","203.0.113.5",null).status());
    }
    @Test void homeAndAliasesNeverQueryModelOrUserRepositoriesInDemoMode() {
        var model=mock(ModelEntityRepository.class);var current=mock(CurrentModelRepository.class);var settings=mock(ModelSettingsService.class);
        var controller=new PageController(model,current,settings);ReflectionTestUtils.setField(controller,"interviewDemo",true);
        assertEquals("forward:/assets/interview/index.html",controller.home());
        assertEquals("forward:/assets/interview/index.html",controller.chatUi(null,null));
        assertEquals("forward:/assets/interview/index.html",controller.dashboard(null,null));
        verifyNoInteractions(model,current,settings);
    }
    @Test void explicitCardsReuseRealVolatilePublishAndAckWithoutRagGeneration() {
        try(var sessions=new ConversateSessionService()) {
            var controller=new ConversateController(sessions);ReflectionTestUtils.setField(controller,"interviewDemo",true);
            var costs=mock(com.example.lms.api.ChatGenerationAdmissionFilter.class);
            when(costs.costCheckCurrentRequest()).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"authentication_required"));
            ReflectionTestUtils.setField(controller,"costs",costs);
            var first=controller.start(null).getBody();assertNotNull(first);
            verifyNoInteractions(costs);
            var output=controller.output(null,first.assistId(),first.epoch()).getBody().subscribe();
            try {
                var accepted=controller.displayCard(null,first.assistId(),new ConversateController.DisplayCard(first.epoch(),"hint","환불은 미개봉 제품에만 적용됩니다.")).getBody();
                assertEquals("환불은 미개봉 제품에만 적용됩니다.",accepted.card().text());
                assertEquals(0,accepted.metrics().generationAttempts());assertEquals(0,accepted.metrics().outputAcks());
                assertThrows(ResponseStatusException.class,()->controller.displayCard(null,first.assistId(),new ConversateController.DisplayCard(first.epoch(),"answer","가".repeat(121))));
                assertThrows(ResponseStatusException.class,()->controller.displayCard(null,first.assistId(),new ConversateController.DisplayCard(first.epoch()+1,"hint","오래된 작업")));
                var ack=controller.acknowledge(null,first.assistId(),new ConversateController.Receipt(accepted.epoch(),accepted.version())).getBody();
                assertEquals(1,ack.metrics().outputAcks());assertEquals(accepted.version(),ack.metrics().lastOutputAckVersion());
                assertEquals(accepted.version(),ack.version(),"ACK must not create a self-sustaining output loop");
                var stopped=controller.control(null,first.assistId(),new ConversateController.Control(first.epoch(),"stop")).getBody();
                assertEquals("STOPPED",stopped.state());assertNull(stopped.card());
            } finally {output.dispose();}
        }
    }
    @Test void originalAuthenticationIsUnchangedOutsideDemoMode() {
        try(var sessions=new ConversateSessionService()) {
            var controller=new ConversateController(sessions);
            assertEquals(401,assertThrows(ResponseStatusException.class,()->controller.start(null)).getStatusCode().value());
            assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.displayCard(null,"unknown",new ConversateController.DisplayCard(1,"hint","synthetic"))).getStatusCode().value());
        }
    }
}
