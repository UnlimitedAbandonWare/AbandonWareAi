package com.example.lms.assist;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
class DisplayRuntimeDiagnosticsTest {
    @Test void htmlRequestAndErrorAreRecordedWithoutQueryCookiesOrRawUserAgent()throws Exception{
        var diagnostics=new DisplayRuntimeDiagnostics(Clock.systemUTC());var request=new MockHttpServletRequest("GET","/assets/display/meta/index.html");request.setQueryString("secret=PRIVATE");request.addHeader("User-Agent","Mozilla/5 Android; wv Chrome/130.0 PRIVATE");request.addHeader("Cookie","PRIVATE");
        diagnostics.doFilter(request,new MockHttpServletResponse(),(a,b)->{});String out=diagnostics.snapshot().toString();assertTrue(out.contains("http_asset"));assertTrue(out.contains("Chrome/130;Android;WebView"));assertFalse(out.contains("PRIVATE"));assertTrue(out.contains("hardwareRendered=not_observed"));
    }
    @Test void clientEventsAreAllowlistedAndBounded(){
        var diagnostics=new DisplayRuntimeDiagnostics();var req=new MockHttpServletRequest("POST","/api/assist/display/relay/diagnostics");req.addHeader("X-Display-Runtime","a".repeat(32));
        assertFalse(diagnostics.client(req,new DisplayRuntimeDiagnostics.ClientEvent("a".repeat(32),"PRIVATE",1,"none",true)));
        assertFalse(diagnostics.client(req,new DisplayRuntimeDiagnostics.ClientEvent("b".repeat(32),"dom_updated",1,"none",true)));
        for(int i=0;i<300;i++)assertTrue(diagnostics.client(req,new DisplayRuntimeDiagnostics.ClientEvent("a".repeat(32),"dom_updated",i,"none",true)));
        assertEquals(128,((java.util.List<?>)diagnostics.snapshot().get("events")).size());assertEquals("dom_callback_only",diagnostics.snapshot().get("ackMeaning"));
    }
}
