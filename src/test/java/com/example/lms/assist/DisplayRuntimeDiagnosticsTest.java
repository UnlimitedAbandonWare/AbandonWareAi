package com.example.lms.assist;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
class DisplayRuntimeDiagnosticsTest {
    @Test void captureFailureSchemaRetainsOnlyBoundedDiagnosticFieldsAndRejectsContent()throws Exception{
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var diagnostics=new DisplayRuntimeDiagnostics();
        var req=new MockHttpServletRequest("POST","/api/assist/display/relay/diagnostics");req.addHeader("X-Display-Runtime","a".repeat(32));
        String payload="{\"runtimeId\":\""+"a".repeat(32)+"\",\"event\":\"capture_error\",\"sequence\":1,\"code\":\"http_403\",\"visible\":true,\"stage\":\"transport\",\"httpStatus\":403,\"epoch\":7,\"producerMismatch\":true,\"lastFrameAgeMs\":42,\"errorCode\":\"event_owner_required\"}";
        var event=mapper.readValue(payload,DisplayRuntimeDiagnostics.ClientEvent.class);assertTrue(diagnostics.client(req,event));
        var row=(java.util.Map<?,?>)((java.util.List<?>)diagnostics.snapshot().get("events")).get(0);
        assertEquals("transport",row.get("stage"));assertEquals(403,row.get("httpStatus"));assertEquals(7L,row.get("epoch"));assertEquals(true,row.get("producerMismatch"));
        for(String field:java.util.List.of("body","text","transcript","grant","cookie","authorization"))assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(payload.substring(0,payload.length()-1)+",\""+field+"\":\"synthetic\"}",DisplayRuntimeDiagnostics.ClientEvent.class));
        for(String invalid:java.util.List.of(payload.replace("transport","private"),payload.replace("403,","999,"),payload.replace("42,","-1,")))assertFalse(diagnostics.client(req,mapper.readValue(invalid,DisplayRuntimeDiagnostics.ClientEvent.class)));
    }
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
